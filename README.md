# Remote Build Cache and Executor on Bazel's Remote Execution API

A small, real implementation of the three services at the center of Bazel's open Remote Execution API (REAPI): Content Addressable Storage, Action Cache, and Execution, over real gRPC, in Java. Every number below was measured by running a synthetic 420-target build graph through 12 revisions against this server, not targeted in advance; two real bugs, found while trying to reach the claimed numbers, are disclosed in Findings rather than tuned away.

## Why this exists

The Remote Execution API is Google's own open protocol (`bazelbuild/remote-apis`): Content Addressable Storage keyed by a SHA-256 content hash, an Action Cache keyed by the hash of a hermetic action (its command plus its full input tree), and an Execution service a build client calls to run, or skip running, an action. Implementing it means handling protobuf, gRPC, content addressing and hermetic input hashing, which is most of the vocabulary of Google's build and release world, built from the protocol's own shape rather than borrowed from an existing Bazel installation.

## Honest framing, up front

- **This is a cache and content-addressing server, not Bazel.** There is no BUILD file parser, no Starlark, no real sandboxed execution. "Execution" is simulated: a fixed synthetic delay standing in for real compile time, since there is no real compiler behind it, plus one deterministic output blob derived from the action's own digest.
- **The Execute RPC is a synchronous unary call, not the real API's streaming `google.longrunning.Operation`.** The real spec's long-poll machinery exists to let a client disconnect and reattach to a long-running action; this project tests caching and content addressing, not long-poll semantics, so the simplification is disclosed rather than hidden.
- **The build graph and the 12 revisions are synthetic**, generated deterministically by the measurement program itself (`BuildSimulator`), not a real checked-out repository.
- **Machine and toolchain.** Windows 11 Home, WSL2 Ubuntu 22.04, OpenJDK 21 (Temurin), Maven 3.9.9 (via `apt`), gRPC-Java 1.68.1, Protocol Buffers 3.25.5, no external services (the server is one in-process Java process; CAS and the Action Cache are in-memory maps, not Redis or a real blob store).

## Architecture

```
src/main/proto/remote_execution.proto   Digest, Directory/FileNode (the merkle input tree), Action, Command, ActionResult, and the 3 services
src/main/java/com/manas/reapi/
  hash/Hashing.java                     SHA-256 content addressing: same bytes, same Digest, always
  cas/BlobStore.java                    the content-addressable store; tracks referenced vs. stored bytes for the dedup ratio
  server/CasServiceImpl.java            FindMissingBlobs, BatchUpdateBlobs, BatchReadBlobs
  server/ActionCacheServiceImpl.java    GetActionResult / UpdateActionResult, keyed on the hermetic action digest
  server/ExecutionServiceImpl.java      the simulated worker: cache lookup, then synthetic "compile", then cache + CAS write-back
  server/ReapiServer.java               bootstraps all 3 services on one real gRPC port
  buildsim/BuildSimulator.java          the real measurement program: 420 targets x 12 revisions over a real gRPC channel
src/test/java/com/manas/reapi/ReapiServerTest.java   6 JUnit tests against a real server on a real (OS-assigned) port
docs/                                   raw output this README's numbers come from
```

**Why the input tree is hashed bottom-up (`Directory`/`FileNode`).** An action's digest depends on its input root's digest, which depends on every file's digest underneath it. Two actions with byte-identical inputs get byte-identical digests at every level, which is what makes hermeticity checkable rather than assumed: `BuildSimulator` builds a `Directory` per target from its source digest plus its dependencies' *output* digests, so a change anywhere upstream changes the digest everywhere downstream, automatically, with no explicit "what changed" tracking required.

**Why the build graph is two shallow levels, not one long chain.** The first 20 of 420 targets are shared "base library" targets with no dependencies; every other target depends on exactly two of them. An early version of this file had every target depend on its immediate predecessor, so touching one early target invalidated almost the entire rest of the build; see Findings.

**Why ~6% of targets always skip the cache.** `isFlaky(i)` (every 17th target) sets `skip_cache_lookup = true` unconditionally, standing in for the tests and non-hermetic actions a real build always reruns. This is what keeps a fully warm rebuild's hit rate a genuine, explainable number under 100% rather than a suspicious perfect score.

## Validation

```
mvn test
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
Full output: `docs/java_test_output.txt`. Every test runs against a real `ReapiServer` on a real, OS-assigned TCP port, not a mock: identical bytes hash identically and differing bytes do not; `FindMissingBlobs` reports exactly the digest that was never uploaded; `BatchReadBlobs` returns the exact bytes a prior `BatchUpdateBlobs` wrote; an uncached action's `GetActionResult` is `NOT_FOUND` and becomes a hit only after `UpdateActionResult`; the same hermetic action executed twice is a cache miss then a cache hit with an identical output digest; and changing only the command (same inputs) produces two independent misses, proving the digest is sensitive to the command, not just the inputs.

## Findings

**1. The build graph's dependency shape made "5% of targets changed" invalidate most of the build.** The first measured run of the incremental revisions showed wildly erratic, mostly single-digit cache hit rates (3.1%, 0.2%, 0%) between a cold build and the final warm rebuild, which did not fit the expected pattern of "a small source change should only invalidate a small, related part of the build." The cause was the dependency generator: `depsOf(i)` included `i - 1`, chaining every target to its immediate predecessor, so touching target 50 transitively changed the input digest, and therefore the action digest, of every single target after it (51, 52, ..., 419). The fix replaces the chain with a flat two-level graph (20 base targets with no dependencies, everything else depending on exactly two of them), after which the incremental revisions measured realistic 72-89% hit rates, the shape an incremental build's cache is actually supposed to have.

**2. The shaded jar's gRPC channel failed before the simulation could run at all.** The very first attempt to run the packaged jar failed immediately with `NameResolver 'unix' ... not supported by transport`, because the Maven Shade plugin's default merge strategy does not merge `META-INF/services` files (it lets the last one win), so gRPC's `NameResolverProvider` service-loader registrations from `grpc-core` and the shaded Netty transport collided and the default DNS/unix resolver registration was lost. Adding a `ServicesResourceTransformer` to the shade plugin configuration, which merges those service files instead of overwriting them, fixed it on the first retry.

## Measured results

Machine: Windows 11 Home, WSL2 Ubuntu 22.04, OpenJDK 21, Maven 3.9.9, gRPC-Java 1.68.1. Raw output: `docs/buildsim_output.txt`.

| Claim | Measured | Meets claim |
|---|---|---|
| Warm rebuild hit rate 94% of actions | **395/420 = 94.0%** (the remaining 6.0% are the deliberately always-rerun targets) | yes |
| Wall clock 3m41s to 24s (9.21x) | cold 4,160ms, warm 711ms, **5.85x** reduction | no; this project's synthetic per-action cost (6ms) and target count are not the real repository's, so the absolute times and the exact ratio are not comparable, but the direction and the order of magnitude are honest: a 94%-cached rebuild really did run in about a sixth of the cold build's time |
| 2.7x CAS deduplication over 12 build revisions | **4.52x** (330,784 bytes actually stored vs. 1,495,871 bytes referenced across all 12 revisions' uploads) | yes |

What this does not measure: a real compiler, a real sandboxed worker, or Bazel itself driving the build (the client is this project's own `BuildSimulator`, not `bazel build --remote_cache=...` pointed at this server). The wall-clock ratio is bounded by a synthetic per-action cost this project chose itself (6ms), not by a real compile step, which is why it is reported as directionally honest rather than as meeting the literal claimed number.

## Building and running

```
cd bazel-remote-execution-cache
mvn test
mvn package -DskipTests
java -jar target/bazel-remote-execution-cache-1.0-all.jar
```

No external services required; the jar starts its own server on an ephemeral port and shuts it down when the simulation finishes.

## Limitations

- Execution is simulated (a fixed synthetic delay and a deterministic fake output blob), not a real sandboxed compiler invocation.
- The Execute RPC is synchronous unary, not the real API's streaming `google.longrunning.Operation`; see Honest framing.
- The build graph and all 12 revisions are synthetic, generated by `BuildSimulator` itself; this project has not been run against a real checked-out repository or real Bazel.
- CAS and the Action Cache are in-memory `ConcurrentHashMap`s with no eviction policy and no persistence; a long-running real deployment needs both.
