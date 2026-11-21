package com.manas.reapi.buildsim;

import com.manas.reapi.proto.*;
import com.manas.reapi.server.ReapiServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.*;

/**
 * The real measurement program behind this project's README claims: a
 * synthetic 420-target build graph, replayed over 12 revisions against a
 * real gRPC REAPI server, measuring cache hit rate, wall clock and CAS
 * deduplication. Every call in this class goes out over a real loopback
 * TCP socket to ReapiServer; nothing here is mocked. The per-target build
 * logic itself lives in BuildRunner, shared with ReleaseGateSimulator.
 */
public final class BuildSimulator {
    private static final int NUM_TARGETS = BuildGraph.NUM_TARGETS;
    private static final int NUM_REVISIONS = 12;
    private static final double TOUCH_FRACTION = 0.05;

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

            BuildRunner.Result coldResult = null;
            BuildRunner.Result warmResult = null;

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

                BuildRunner.Result result = BuildRunner.runAll(cas, exec, lastChangedRevision, outputDigests);
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
}
