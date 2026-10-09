package com.manas.reapi.server;

import com.google.protobuf.ByteString;
import com.manas.reapi.cas.BlobStore;
import com.manas.reapi.hash.Hashing;
import com.manas.reapi.proto.*;
import io.grpc.stub.StreamObserver;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The simulated worker. A real executor would fetch the input tree from
 * CAS and run the command in a sandbox; this one "executes" by sleeping
 * a fixed synthetic cost (standing in for real compile time, since there
 * is no real compiler here) and writing one deterministic output blob
 * derived from the action digest, which is enough to exercise caching
 * and content addressing honestly without needing a real toolchain.
 */
public final class ExecutionServiceImpl extends ExecutionGrpc.ExecutionImplBase {
    public static final long SYNTHETIC_EXECUTION_MILLIS = 6;

    private final ActionCacheServiceImpl actionCache;
    private final BlobStore store;

    public ExecutionServiceImpl(ActionCacheServiceImpl actionCache, BlobStore store) {
        this.actionCache = actionCache;
        this.store = store;
    }

    @Override
    public void execute(ExecuteRequest request, StreamObserver<ActionResult> resp) {
        Digest actionDigest = Hashing.digestOf(request.getAction().toByteArray());

        if (!request.getSkipCacheLookup()) {
            Optional<ActionResult> cached = actionCache.lookup(actionDigest);
            if (cached.isPresent()) {
                resp.onNext(cached.get().toBuilder().setCachedResult(true).build());
                resp.onCompleted();
                return;
            }
        }

        try {
            Thread.sleep(SYNTHETIC_EXECUTION_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        byte[] outputBytes = ("compiled:" + actionDigest.getHash()).getBytes(StandardCharsets.UTF_8);
        Digest outputDigest = Hashing.digestOf(outputBytes);
        store.put(outputDigest, outputBytes);

        ActionResult result = ActionResult.newBuilder()
                .addOutputFiles(OutputFile.newBuilder()
                        .setPath("out")
                        .setDigest(outputDigest)
                        .setIsExecutable(false)
                        .build())
                .setExitCode(0)
                .setCachedResult(false)
                .build();
        actionCache.store(actionDigest, result);

        resp.onNext(result);
        resp.onCompleted();
    }
}
