package com.manas.reapi.buildsim;

import com.manas.reapi.proto.*;
import com.manas.reapi.server.ReapiServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.*;

/**
 * The affected-test release gate's own measurement program. It injects a
 * real bug (a source edit to one target) into the identical 420-target
 * build graph BuildSimulator measures, once per revision for 60
 * revisions, over the real gRPC server.
 *
 * Two independent answers are computed for every revision:
 *   - the SLOW, OBVIOUSLY CORRECT one: rebuild all 420 targets for real,
 *     and see which output digests actually changed versus the previous
 *     build. This is the ground truth of which tests a real CI run would
 *     have to fail.
 *   - the FAST one the release gate actually ships: BuildGraph's
 *     transitive reachability from the one target that changed, with no
 *     rebuild at all.
 *
 * A "missed failure" is a test the ground truth says would fail that the
 * fast selection did not pick. The claim this measures is 0 of those
 * across 60 revisions.
 */
public final class ReleaseGateSimulator {
    private static final int NUM_REVISIONS = 60;

    public static void main(String[] args) throws Exception {
        int port = 50062;
        ReapiServer server = new ReapiServer(port);
        server.start();
        try {
            ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub cas =
                    ContentAddressableStorageGrpc.newBlockingStub(channel);
            ExecutionGrpc.ExecutionBlockingStub exec = ExecutionGrpc.newBlockingStub(channel);

            BuildGraph graph = new BuildGraph();
            TestSuite suite = new TestSuite(7_777L);

            int[] lastChangedRevision = new int[BuildGraph.NUM_TARGETS];
            Digest[] outputDigests = new Digest[BuildGraph.NUM_TARGETS];

            // Baseline: every target at revision 0, built once.
            BuildRunner.runAll(cas, exec, lastChangedRevision, outputDigests);
            String[] priorDigests = snapshot(outputDigests);

            Random bugPicker = new Random(2026);
            int totalMissedFailures = 0;
            int totalGroundTruthFailingTests = 0;
            int totalSelectedTests = 0;
            int revisionsWithARealFailure = 0;

            for (int rev = 1; rev <= NUM_REVISIONS; rev++) {
                int buggyTarget = bugPicker.nextInt(BuildGraph.NUM_TARGETS);
                lastChangedRevision[buggyTarget] = rev; // guarantees new, different source bytes

                BuildRunner.runAll(cas, exec, lastChangedRevision, outputDigests);
                String[] newDigests = snapshot(outputDigests);

                Set<Integer> actuallyChanged = diff(priorDigests, newDigests);
                Set<Integer> groundTruthFailingTests = suite.testsTouching(actuallyChanged);
                Set<Integer> selectedTests = AffectedTestSelector.select(graph, suite, Set.of(buggyTarget));

                Set<Integer> missed = new HashSet<>(groundTruthFailingTests);
                missed.removeAll(selectedTests);

                if (!missed.isEmpty()) {
                    System.out.printf("revision %2d: MISSED %d failing test(s) that selection did not pick: %s%n",
                            rev, missed.size(), missed);
                }
                totalMissedFailures += missed.size();
                totalGroundTruthFailingTests += groundTruthFailingTests.size();
                totalSelectedTests += selectedTests.size();
                if (!groundTruthFailingTests.isEmpty()) revisionsWithARealFailure++;

                priorDigests = newDigests;
            }

            System.out.println();
            System.out.println("=== Affected-test release gate: 60 injected-bug revisions over a 420-target graph, " + TestSuite.NUM_TESTS + " tests ===");
            System.out.printf("revisions where the bug actually changed at least one test's output: %d / %d%n",
                    revisionsWithARealFailure, NUM_REVISIONS);
            System.out.printf("total ground-truth failing tests across all revisions: %d%n", totalGroundTruthFailingTests);
            System.out.printf("total tests selected by the fast graph-based gate: %d (vs %d if every revision ran the full suite)%n",
                    totalSelectedTests, NUM_REVISIONS * TestSuite.NUM_TESTS);
            System.out.printf("MISSED_FAILURES: %d%n", totalMissedFailures);

            channel.shutdownNow();
        } finally {
            server.stop();
        }
    }

    private static String[] snapshot(Digest[] outputDigests) {
        String[] out = new String[outputDigests.length];
        for (int i = 0; i < outputDigests.length; i++) {
            out[i] = outputDigests[i].getHash();
        }
        return out;
    }

    private static Set<Integer> diff(String[] before, String[] after) {
        Set<Integer> changed = new HashSet<>();
        for (int i = 0; i < before.length; i++) {
            if (!before[i].equals(after[i])) changed.add(i);
        }
        return changed;
    }
}
