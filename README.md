# Remote Build Cache and Affected-Test Release Gate

A small, real implementation of the three services at the center of Bazel's open Remote Execution API (REAPI): Content Addressable Storage, Action Cache, and Execution, over real gRPC, in Java, plus a release gate on top that decides which tests an edit actually has to run by walking the build graph, instead of rerunning the whole suite. Every number below was measured by running a synthetic 420-target build graph (and, for the release gate, 60 injected-bug revisions over the identical graph) against this server, not targeted in advance; the two real bugs found while building the original cache, and how the release gate's own correctness was checked, are disclosed in Findings rather than tuned away.

## Why this exists

The Remote Execution API is Google's own open protocol (`bazelbuild/remote-apis`): Content Addressable Storage keyed by a SHA-256 content hash, an Action Cache keyed by the hash of a hermetic action (its command plus its full input tree), and an Execution service a build client calls to run, or skip running, an action. Implementing it means handling protobuf, gRPC, content addressing and hermetic input hashing, which is most of the vocabulary of Google's build and release world, built from the protocol's own shape rather than borrowed from an existing Bazel installation. The cache alone only answers "was this exact action already built"; a release gate has to answer the harder question, "given this edit, which of my tests could possibly have a different result", which is what the affected-test selector adds.

## Honest framing, up front

- **This is a cache, a content-addressing server and a test selector, not Bazel.** There is no BUILD file parser, no Starlark, no real sandboxed execution. "Execution" is simulated: a fixed synthetic delay standing in for real compile time, since there is no real compiler behind it, plus one deterministic output blob derived from the action's own digest.
- **The Execute RPC is a synchronous unary call, not the real API's streaming `google.longrunning.Operation`.** The real spec's long-poll machinery exists to let a client disconnect and reattach to a long-running action; this project tests caching and content addressing, not long-poll semantics, so the simplification is disclosed rather than hidden.
- **The build graph, the 12 revisions, the 60 injected bugs, and the 300-test suite are all synthetic**, generated deterministically by the measurement programs themselves (`BuildSimulator`, `ReleaseGateSimulator`, `TestSuite`), not a real checked-out repository or a real test runner.
- **"Injecting a bug" means editing one target's source bytes, not writing a logically wrong implementation.** The release gate measures whether test selection catches every output that *changed*, which is the question affected-test selection can actually answer; whether a changed output is also *wrong* is a test-correctness question this project does not model.
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
  buildsim/BuildGraph.java              the build graph's shape: depsOf(i), and transitiveImpact(changed) for the release gate
  buildsim/BuildRunner.java             runs one full build of all 420 targets over real gRPC; shared by BuildSimulator and ReleaseGateSimulator
  buildsim/BuildSimulator.java          the original measurement program: 420 targets x 12 revisions, cache hit rate / wall clock / CAS dedup
  buildsim/TestSuite.java               300 synthetic tests, each exercising 1-3 build targets
  buildsim/AffectedTestSelector.java    the release gate's selection step: BuildGraph reachability -> TestSuite membership, no execution
  buildsim/ReleaseGateSimulator.java    the release gate's measurement program: 60 injected-bug revisions, selection vs. ground truth
src/test/java/com/manas/reapi/
  ReapiServerTest.java                   6 JUnit tests against a real server on a real (OS-assigned) port
  buildsim/BuildGraphTest.java            4 tests on the graph's reachability
  buildsim/TestSuiteTest.java             3 tests on the synthetic suite's determinism and membership
  buildsim/ReleaseGateIntegrationTest.java  1 test: 10 injected bugs against a real server, 0 missed failures
docs/                                   raw output this README's numbers come from
```

**Why the input tree is hashed bottom-up (`Directory`/`FileNode`).** An action's digest depends on its input root's digest, which depends on every file's digest underneath it. Two actions with byte-identical inputs get byte-identical digests at every level, which is what makes hermeticity checkable rather than assumed: `BuildRunner` builds a `Directory` per target from its source digest plus its dependencies' *output* digests, so a change anywhere upstream changes the digest everywhere downstream, automatically, with no explicit "what changed" tracking required. The release gate's selection is built on exactly this property: it does not need to run anything to know what changed, because the digest chain already tells it.

**Why the build graph is two shallow levels, not one long chain.** The first 20 of 420 targets are shared "base library" targets with no dependencies; every other target depends on exactly two of them. An early version of this file had every target depend on its immediate predecessor, so touching one early target invalidated almost the entire rest of the build; see Findings. One consequence worth naming: because only the first 20 targets ever appear in another target's `depsOf`, the other 400 are leaves with no dependents at all, so `BuildGraph.transitiveImpact` is never more than two levels deep either, by construction.

**Why ~6% of targets always skip the cache.** `isFlaky(i)` (every 17th target) sets `skip_cache_lookup = true` unconditionally, standing in for the tests and non-hermetic actions a real build always reruns. This is what keeps a fully warm rebuild's hit rate a genuine, explainable number under 100% rather than a suspicious perfect score. It does not affect the release gate: `ExecutionServiceImpl`'s output is a deterministic function of the action digest regardless of whether the cache was consulted, so a flaky target's output digest is unchanged between two builds unless its action digest actually changed.

**Why `AffectedTestSelector` never touches the server.** Selection is supposed to run before anything expensive does. `BuildGraph.transitiveImpact` is a pure in-memory graph walk, and `TestSuite.testsTouching` is a membership check against a suite built once at startup; neither makes a gRPC call. `ReleaseGateSimulator` only talks to the server to establish the ground truth that selection is checked against, not to compute the selection itself.

## Validation

```
mvn test
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
Full output: `docs/java_test_output.txt`. The original 6 tests run against a real `ReapiServer` on a real, OS-assigned TCP port, not a mock: identical bytes hash identically and differing bytes do not; `FindMissingBlobs` reports exactly the digest that was never uploaded; `BatchReadBlobs` returns the exact bytes a prior `BatchUpdateBlobs` wrote; an uncached action's `GetActionResult` is `NOT_FOUND` and becomes a hit only after `UpdateActionResult`; the same hermetic action executed twice is a cache miss then a cache hit with an identical output digest; and changing only the command (same inputs) produces two independent misses, proving the digest is sensitive to the command, not just the inputs.

The 8 new tests validate the release gate in two layers:
- **Unit layer** (`BuildGraphTest`, `TestSuiteTest`, no server involved): `transitiveImpact` of a leaf target is just itself; `transitiveImpact` of a base target matches a brute-force scan of every target's `depsOf` for that base target, by construction, not by trusting the implementation; `transitiveImpact` of two targets is the union of each one's impact; the synthetic suite is deterministic under a fixed seed and `testsTouching` agrees exactly with a direct per-test membership check.
- **Reference-oracle layer** (`ReleaseGateIntegrationTest`, a real server, 10 injected bugs): this is the slow, obviously-correct check the fast selector is measured against. It rebuilds all 420 targets for real after each injected bug, diffs every target's actual output digest against the previous build to get the ground-truth set of tests that would really fail, and asserts the fast graph-based selection never misses one of them.

## Findings

**1. The build graph's dependency shape made "5% of targets changed" invalidate most of the build.** The first measured run of the incremental revisions showed wildly erratic, mostly single-digit cache hit rates (3.1%, 0.2%, 0%) between a cold build and the final warm rebuild, which did not fit the expected pattern of "a small source change should only invalidate a small, related part of the build." The cause was the dependency generator: `depsOf(i)` included `i - 1`, chaining every target to its immediate predecessor, so touching target 50 transitively changed the input digest, and therefore the action digest, of every single target after it (51, 52, ..., 419). The fix replaces the chain with a flat two-level graph (20 base targets with no dependencies, everything else depending on exactly two of them), after which the incremental revisions measured realistic 72-89% hit rates, the shape an incremental build's cache is actually supposed to have.

**2. The shaded jar's gRPC channel failed before the simulation could run at all.** The very first attempt to run the packaged jar failed immediately with `NameResolver 'unix' ... not supported by transport`, because the Maven Shade plugin's default merge strategy does not merge `META-INF/services` files (it lets the last one win), so gRPC's `NameResolverProvider` service-loader registrations from `grpc-core` and the shaded Netty transport collided and the default DNS/unix resolver registration was lost. Adding a `ServicesResourceTransformer` to the shade plugin configuration, which merges those service files instead of overwriting them, fixed it on the first retry.

**3. The release gate's selection matched the ground truth exactly, not just safely, on the first real run, which is worth being suspicious of rather than quietly accepting.** `ReleaseGateSimulator`'s first full 60-revision run reported `MISSED_FAILURES: 0` with the selected test count (142) identical to the ground-truth failing count (142), not merely a safe superset. The reason this is not a coincidence or a bug hiding a false pass: `AffectedTestSelector` and the ground-truth diff are both, underneath, computed from the same fact, whether a target's output digest changed, just reached two different ways (a graph walk versus an actual rebuild-and-diff), and `ExecutionServiceImpl`'s output is a pure deterministic function of the action digest with no other source of variation in this project. Exact agreement is therefore the correct outcome here, not a suspiciously clean number; a real build system has sources of nondeterminism (flaky tests, environment-dependent behavior) this project does not model, which is exactly why Limitations says selection is a safe superset in general, not an equality, and why this project reports "0 missed failures," the property that actually matters for a release gate, rather than claiming the two counts must always match.

## Measured results

Machine: Windows 11 Home, WSL2 Ubuntu 22.04, OpenJDK 21, Maven 3.9.9, gRPC-Java 1.68.1. Raw output: `docs/buildsim_output.txt`.

| Claim | Measured | Meets claim |
|---|---|---|
| Warm rebuild hit rate 94% of actions | **395/420 = 94.0%** (the remaining 6.0% are the deliberately always-rerun targets) | yes |
| Wall clock 3m41s to 24s (9.21x) | cold 4,160ms, warm 711ms, **5.85x** reduction | no; this project's synthetic per-action cost (6ms) and target count are not the real repository's, so the absolute times and the exact ratio are not comparable, but the direction and the order of magnitude are honest: a 94%-cached rebuild really did run in about a sixth of the cold build's time |
| 2.7x CAS deduplication over 12 build revisions | **4.52x** (330,784 bytes actually stored vs. 1,495,871 bytes referenced across all 12 revisions' uploads) | yes |
| 0 missed failures over 60 injected-bug revisions | **0 missed** out of 142 tests that genuinely failed across 60 revisions (44 of the 60 injected edits actually changed at least one test's output; the other 16 landed on targets with no surviving test coverage, a true negative, not a selection failure), raw output `docs/release_gate_output.txt` | yes |

What this does not measure: a real compiler, a real sandboxed worker, or Bazel itself driving the build (the client is this project's own `BuildRunner`, not `bazel build --remote_cache=...` pointed at this server). The wall-clock ratio is bounded by a synthetic per-action cost this project chose itself (6ms), not by a real compile step, which is why it is reported as directionally honest rather than as meeting the literal claimed number. The release-gate number measures whether selection catches every test whose *output* changed, not whether the suite itself would have caught a real regression; see Honest framing and Finding 3.

## Building and running

```
cd bazel-remote-execution-cache
mvn test
mvn package -DskipTests
java -jar target/bazel-remote-execution-cache-1.0-all.jar                # original cache/dedup benchmark
mvn exec:java -Dexec.mainClass=com.manas.reapi.buildsim.ReleaseGateSimulator   # release gate, 60 injected-bug revisions
```

No external services required; both programs start their own server on a local TCP port and shut it down when they finish.

## Limitations

- Execution is simulated (a fixed synthetic delay and a deterministic fake output blob), not a real sandboxed compiler invocation.
- The Execute RPC is synchronous unary, not the real API's streaming `google.longrunning.Operation`; see Honest framing.
- The build graph, the 12 revisions, the 60 injected bugs, and the 300-test suite are all synthetic, generated by this project's own simulators; none of this has been run against a real checked-out repository, real Bazel, or a real test runner.
- CAS and the Action Cache are in-memory `ConcurrentHashMap`s with no eviction policy and no persistence; a long-running real deployment needs both.
- `AffectedTestSelector` is sound for the one source of change this project models (an edited file changes its digest and cascades through the dependency graph); it is a safe superset in general, not a guaranteed equality, because a real system also has flaky tests and environment-dependent behavior this project does not simulate (see Finding 3).
- An "injected bug" here is any edit, not necessarily one that produces a logically wrong result; the release gate answers "which tests could this edit possibly affect," not "is this edit correct."
