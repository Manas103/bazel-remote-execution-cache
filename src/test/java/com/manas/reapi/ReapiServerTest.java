package com.manas.reapi;

import com.google.protobuf.ByteString;
import com.manas.reapi.hash.Hashing;
import com.manas.reapi.proto.*;
import com.manas.reapi.server.ReapiServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ReapiServerTest {
    private ReapiServer server;
    private ManagedChannel channel;
    private ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub cas;
    private ActionCacheGrpc.ActionCacheBlockingStub actionCache;
    private ExecutionGrpc.ExecutionBlockingStub exec;

    @BeforeEach
    void setUp() throws Exception {
        server = new ReapiServer(0); // OS-assigned free port
        server.start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
        cas = ContentAddressableStorageGrpc.newBlockingStub(channel);
        actionCache = ActionCacheGrpc.newBlockingStub(channel);
        exec = ExecutionGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.stop();
    }

    @Test
    void sameBytesHashToTheSameDigest() {
        byte[] a = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] b = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals(Hashing.digestOf(a).getHash(), Hashing.digestOf(b).getHash());
        byte[] c = "hellp".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(Hashing.digestOf(a).getHash(), Hashing.digestOf(c).getHash());
    }

    @Test
    void findMissingBlobsReportsOnlyWhatIsActuallyMissing() {
        byte[] data = "present".getBytes(StandardCharsets.UTF_8);
        Digest present = Hashing.digestOf(data);
        cas.batchUpdateBlobs(BatchUpdateBlobsRequest.newBuilder()
                .addRequests(BatchUpdateBlobsRequest.Request.newBuilder().setDigest(present).setData(ByteString.copyFrom(data)).build())
                .build());
        Digest missing = Hashing.digestOf("absent".getBytes(StandardCharsets.UTF_8));

        FindMissingBlobsResponse resp = cas.findMissingBlobs(FindMissingBlobsRequest.newBuilder()
                .addBlobDigests(present).addBlobDigests(missing).build());

        assertEquals(1, resp.getMissingBlobDigestsCount());
        assertEquals(missing.getHash(), resp.getMissingBlobDigests(0).getHash());
    }

    @Test
    void batchReadReturnsExactBytesWrittenByBatchUpdate() {
        byte[] data = "round trip me".getBytes(StandardCharsets.UTF_8);
        Digest d = Hashing.digestOf(data);
        cas.batchUpdateBlobs(BatchUpdateBlobsRequest.newBuilder()
                .addRequests(BatchUpdateBlobsRequest.Request.newBuilder().setDigest(d).setData(ByteString.copyFrom(data)).build())
                .build());

        BatchReadBlobsResponse resp = cas.batchReadBlobs(BatchReadBlobsRequest.newBuilder().addDigests(d).build());
        assertEquals(1, resp.getResponsesCount());
        assertEquals(0, resp.getResponses(0).getStatusCode());
        assertArrayEquals(data, resp.getResponses(0).getData().toByteArray());
    }

    @Test
    void actionCacheMissThenHitAfterUpdate() {
        Digest actionDigest = Hashing.digestOf("some-action".getBytes(StandardCharsets.UTF_8));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> actionCache.getActionResult(GetActionResultRequest.newBuilder().setActionDigest(actionDigest).build()));
        assertEquals(io.grpc.Status.Code.NOT_FOUND, ex.getStatus().getCode());

        ActionResult stored = ActionResult.newBuilder().setExitCode(0).build();
        actionCache.updateActionResult(UpdateActionResultRequest.newBuilder()
                .setActionDigest(actionDigest).setActionResult(stored).build());

        ActionResult fetched = actionCache.getActionResult(GetActionResultRequest.newBuilder().setActionDigest(actionDigest).build());
        assertTrue(fetched.getCachedResult());
        assertEquals(0, fetched.getExitCode());
    }

    @Test
    void secondExecuteOfTheIdenticalHermeticActionIsACacheHit() {
        Command command = Command.newBuilder().addArguments("echo").addArguments("hi").build();
        byte[] cmdBytes = command.toByteArray();
        Digest commandDigest = Hashing.digestOf(cmdBytes);
        cas.batchUpdateBlobs(BatchUpdateBlobsRequest.newBuilder()
                .addRequests(BatchUpdateBlobsRequest.Request.newBuilder().setDigest(commandDigest).setData(ByteString.copyFrom(cmdBytes)).build())
                .build());
        Digest inputRoot = Hashing.digestOf("empty-input-tree".getBytes(StandardCharsets.UTF_8));
        Action action = Action.newBuilder().setCommandDigest(commandDigest).setInputRootDigest(inputRoot).build();

        ActionResult first = exec.execute(ExecuteRequest.newBuilder().setAction(action).build());
        assertFalse(first.getCachedResult());

        ActionResult second = exec.execute(ExecuteRequest.newBuilder().setAction(action).build());
        assertTrue(second.getCachedResult());
        assertEquals(first.getOutputFiles(0).getDigest().getHash(), second.getOutputFiles(0).getDigest().getHash());
    }

    @Test
    void changingTheCommandChangesTheActionDigestAndMissesTheCache() {
        Digest inputRoot = Hashing.digestOf("same-inputs".getBytes(StandardCharsets.UTF_8));

        Command cmd1 = Command.newBuilder().addArguments("compile").addArguments("v1").build();
        Digest cmdDigest1 = Hashing.digestOf(cmd1.toByteArray());
        ActionResult r1 = exec.execute(ExecuteRequest.newBuilder()
                .setAction(Action.newBuilder().setCommandDigest(cmdDigest1).setInputRootDigest(inputRoot).build())
                .build());
        assertFalse(r1.getCachedResult());

        Command cmd2 = Command.newBuilder().addArguments("compile").addArguments("v2").build();
        Digest cmdDigest2 = Hashing.digestOf(cmd2.toByteArray());
        ActionResult r2 = exec.execute(ExecuteRequest.newBuilder()
                .setAction(Action.newBuilder().setCommandDigest(cmdDigest2).setInputRootDigest(inputRoot).build())
                .build());
        assertFalse(r2.getCachedResult());
    }
}
