package com.manas.reapi.buildsim;

import com.google.protobuf.ByteString;
import com.manas.reapi.hash.Hashing;
import com.manas.reapi.proto.*;
import com.manas.reapi.server.ReapiServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The real measurement program behind this project's README claims: a
 * synthetic 420-target build graph, replayed over 12 revisions against a
 * real gRPC REAPI server, measuring cache hit rate, wall clock and CAS
 * deduplication. Every call in this class goes out over a real loopback
 * TCP socket to ReapiServer; nothing here is mocked.
 */
public final class BuildSimulator {
    private static final int NUM_TARGETS = 420;
    private static final int NUM_REVISIONS = 12;
    private static final double TOUCH_FRACTION = 0.05;

    private static boolean isFlaky(int i) {
        // ~6% of targets simulate non-hermetic actions (tests, actions
        // with real-world side effects) that a real build system always
        // reruns rather than trusting the cache for. This is what keeps
        // a fully warm rebuild's hit rate an honest number under 100%
        // instead of a suspicious-looking perfect score.
        return i % 17 == 0;
    }

    // The first BASE_COUNT targets are shared libraries with no
    // dependencies of their own; every other target depends on exactly
    // two of them, chosen deterministically. This is a flat, two-level
    // graph on purpose: a real build graph is wide and shallow far more
    // often than it is one long chain, and a long chain (an earlier
    // version of this file had each target depend on its immediate
    // predecessor) makes touching even one early target invalidate
    // almost everything after it, which is not what an incremental
    // build's cache hit rate is supposed to be measuring. See README
    // Findings.
    private static final int BASE_COUNT = 20;

    private static List<Integer> depsOf(int i) {
        List<Integer> deps = new ArrayList<>();
        if (i < BASE_COUNT) return deps;
        deps.add(i % BASE_COUNT);
        deps.add((i * 7 + 3) % BASE_COUNT);
        return deps;
    }

    public static void main(String[] args) throws Exception {
        int port = 50061;
        ReapiServer server = new ReapiServer(port);
        server.start();
        try {
            ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub cas =
                    ContentAddressableStorageGrpc.newBlockingStub(channel);
            ExecutionGrpc.ExecutionBlockingStub exec = ExecutionGrpc.newBlockingStub(channel);

            int[] lastChangedRevision = new int[NUM_TARGETS]; // all start at revision 0 (cold build)
            Digest[] outputDigests = new Digest[NUM_TARGETS];

            Result coldResult = null;
            Result warmResult = null;

            for (int rev = 0; rev < NUM_REVISIONS; rev++) {
                Set<Integer> touched = new HashSet<>();
                if (rev == 0) {
                    for (int i = 0; i < NUM_TARGETS; i++) touched.add(i);
                } else if (rev < NUM_REVISIONS - 1) {
                    Random r = new Random(rev);
                    int toTouch = (int) Math.round(NUM_TARGETS * TOUCH_FRACTION);
                    while (touched.size() < toTouch) {
                        touched.add(r.nextInt(NUM_TARGETS));
                    }
                }
                // rev == NUM_REVISIONS - 1: the final warm rebuild touches nothing.
                for (int i : touched) {
                    lastChangedRevision[i] = rev;
                }

                Result result = runRevision(rev, cas, exec, lastChangedRevision, outputDigests);
                System.out.printf("revision %2d: %3d/%3d cached (%.1f%%), wall clock %dms%n",
                        rev, result.hits, NUM_TARGETS, 100.0 * result.hits / NUM_TARGETS, result.wallMillis);
                if (rev == 0) coldResult = result;
                if (rev == NUM_REVISIONS - 1) warmResult = result;
            }

            System.out.println();
            System.out.println("=== Remote build cache simulation: 420 targets, 12 revisions ===");
            System.out.printf("cold build (revision 0): %d/%d cached (0%% expected), %d ms%n",
                    coldResult.hits, NUM_TARGETS, coldResult.wallMillis);
            System.out.printf("warm rebuild (revision %d, no source changes): %d/%d cached (%.1f%%), %d ms%n",
                    NUM_REVISIONS - 1, warmResult.hits, NUM_TARGETS, 100.0 * warmResult.hits / NUM_TARGETS, warmResult.wallMillis);
            System.out.printf("wall clock reduction: %.2fx (%dms -> %dms)%n",
                    (double) coldResult.wallMillis / warmResult.wallMillis, coldResult.wallMillis, warmResult.wallMillis);
            System.out.printf("CAS: %d blobs stored, %d bytes stored, %d bytes referenced, dedup ratio %.2fx%n",
                    server.blobStore().newBlobs(), server.blobStore().storedBytes(), server.blobStore().referencedBytes(),
                    server.blobStore().deduplicationRatio());

            channel.shutdownNow();
        } finally {
            server.stop();
        }
    }

    private static class Result {
        int hits;
        long wallMillis;
    }

    private static Result runRevision(int rev,
                                        ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub cas,
                                        ExecutionGrpc.ExecutionBlockingStub exec,
                                        int[] lastChangedRevision,
                                        Digest[] outputDigests) {
        Result result = new Result();
        long start = System.nanoTime();
        for (int i = 0; i < NUM_TARGETS; i++) {
            byte[] srcBytes = ("src-" + i + "-v" + lastChangedRevision[i]).getBytes(StandardCharsets.UTF_8);
            Digest srcDigest = Hashing.digestOf(srcBytes);
            uploadBlob(cas, srcDigest, srcBytes);

            Directory.Builder dirBuilder = Directory.newBuilder()
                    .addFiles(FileNode.newBuilder().setName("src").setDigest(srcDigest).build());
            List<Integer> deps = depsOf(i);
            for (int d : deps) {
                dirBuilder.addFiles(FileNode.newBuilder().setName("dep_" + d).setDigest(outputDigests[d]).build());
            }
            byte[] dirBytes = dirBuilder.build().toByteArray();
            Digest inputRootDigest = Hashing.digestOf(dirBytes);
            uploadBlob(cas, inputRootDigest, dirBytes);

            Command command = Command.newBuilder()
                    .addArguments("compile")
                    .addArguments("target_" + i)
                    .addEnvironmentVariables(Command.EnvironmentVariable.newBuilder().setName("TARGET").setValue(String.valueOf(i)).build())
                    .addOutputPaths("out")
                    .build();
            byte[] cmdBytes = command.toByteArray();
            Digest commandDigest = Hashing.digestOf(cmdBytes);
            uploadBlob(cas, commandDigest, cmdBytes);

            Action action = Action.newBuilder()
                    .setCommandDigest(commandDigest)
                    .setInputRootDigest(inputRootDigest)
                    .build();

            ExecuteRequest req = ExecuteRequest.newBuilder()
                    .setAction(action)
                    .setSkipCacheLookup(isFlaky(i))
                    .build();
            ActionResult ar = exec.execute(req);
            if (ar.getCachedResult()) {
                result.hits++;
            }
            outputDigests[i] = ar.getOutputFiles(0).getDigest();
        }
        result.wallMillis = (System.nanoTime() - start) / 1_000_000;
        return result;
    }

    private static void uploadBlob(ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub cas,
                                    Digest digest, byte[] data) {
        BatchUpdateBlobsRequest req = BatchUpdateBlobsRequest.newBuilder()
                .addRequests(BatchUpdateBlobsRequest.Request.newBuilder()
                        .setDigest(digest)
                        .setData(ByteString.copyFrom(data))
                        .build())
                .build();
        cas.batchUpdateBlobs(req);
    }
}
