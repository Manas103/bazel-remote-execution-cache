package com.manas.reapi.buildsim;

import com.google.protobuf.ByteString;
import com.manas.reapi.hash.Hashing;
import com.manas.reapi.proto.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs the full 420-target build, in target-id order, over a real gRPC
 * REAPI server. Extracted so BuildSimulator (the original cache-rate
 * benchmark) and ReleaseGateSimulator (the affected-test release gate)
 * drive the identical build logic; neither can quietly diverge from the
 * other's notion of "how a target is built".
 */
public final class BuildRunner {
    private BuildRunner() {}

    public static boolean isFlaky(int i) {
        // ~6% of targets simulate non-hermetic actions that a real build
        // system always reruns rather than trusting the cache for.
        return i % 17 == 0;
    }

    public static final class Result {
        public int hits;
        public long wallMillis;
    }

    /**
     * Rebuilds every target. {@code lastChangedRevision[i]} determines
     * target i's source bytes for this build; {@code outputDigests} is
     * both read (for already-built dependencies, in target order, since
     * every target's dependencies have a strictly smaller id) and
     * overwritten in place with this build's output digests.
     */
    public static Result runAll(ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub cas,
                                 ExecutionGrpc.ExecutionBlockingStub exec,
                                 int[] lastChangedRevision,
                                 Digest[] outputDigests) {
        Result result = new Result();
        long start = System.nanoTime();
        for (int i = 0; i < BuildGraph.NUM_TARGETS; i++) {
            byte[] srcBytes = ("src-" + i + "-v" + lastChangedRevision[i]).getBytes(StandardCharsets.UTF_8);
            Digest srcDigest = Hashing.digestOf(srcBytes);
            uploadBlob(cas, srcDigest, srcBytes);

            Directory.Builder dirBuilder = Directory.newBuilder()
                    .addFiles(FileNode.newBuilder().setName("src").setDigest(srcDigest).build());
            List<Integer> deps = BuildGraph.depsOf(i);
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
