package com.manas.reapi.buildsim;

import com.manas.reapi.proto.*;
import com.manas.reapi.server.ReapiServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The small, fast version of what ReleaseGateSimulator measures at scale:
 * against a real ReapiServer (not a mock), inject a handful of real
 * source edits, rebuild for real, and check that the fast graph-based
 * test selection never misses a test whose output genuinely changed.
 */
class ReleaseGateIntegrationTest {
    private ReapiServer server;
    private ManagedChannel channel;
    private ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub cas;
    private ExecutionGrpc.ExecutionBlockingStub exec;

    @BeforeEach
    void setUp() throws Exception {
        server = new ReapiServer(0);
        server.start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
        cas = ContentAddressableStorageGrpc.newBlockingStub(channel);
        exec = ExecutionGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.stop();
    }

    @Test
    void selectionNeverMissesARealOutputChangeOverTenInjectedBugs() {
        BuildGraph graph = new BuildGraph();
        TestSuite suite = new TestSuite(123L);

        int[] lastChangedRevision = new int[BuildGraph.NUM_TARGETS];
        Digest[] outputDigests = new Digest[BuildGraph.NUM_TARGETS];
        BuildRunner.runAll(cas, exec, lastChangedRevision, outputDigests);
        String[] prior = snapshot(outputDigests);

        Random bugPicker = new Random(0xC0FFEE);
        int missedTotal = 0;
        int groundTruthTotal = 0;

        for (int rev = 1; rev <= 10; rev++) {
            int buggyTarget = bugPicker.nextInt(BuildGraph.NUM_TARGETS);
            lastChangedRevision[buggyTarget] = rev;

            BuildRunner.runAll(cas, exec, lastChangedRevision, outputDigests);
            String[] after = snapshot(outputDigests);

            Set<Integer> actuallyChanged = new HashSet<>();
            for (int i = 0; i < prior.length; i++) {
                if (!prior[i].equals(after[i])) actuallyChanged.add(i);
            }
            assertTrue(actuallyChanged.contains(buggyTarget), "the touched target's own output must change");

            Set<Integer> groundTruthFailingTests = suite.testsTouching(actuallyChanged);
            Set<Integer> selectedTests = AffectedTestSelector.select(graph, suite, Set.of(buggyTarget));

            Set<Integer> missed = new HashSet<>(groundTruthFailingTests);
            missed.removeAll(selectedTests);
            missedTotal += missed.size();
            groundTruthTotal += groundTruthFailingTests.size();

            prior = after;
        }

        assertEquals(0, missedTotal, "the fast selection must never miss a test whose output really changed");
        assertTrue(groundTruthTotal > 0, "at least one of the 10 injected bugs should have actually failed a test, "
                + "or this test is not exercising the thing it claims to");
    }

    private static String[] snapshot(Digest[] outputDigests) {
        String[] out = new String[outputDigests.length];
        for (int i = 0; i < outputDigests.length; i++) out[i] = outputDigests[i].getHash();
        return out;
    }
}
