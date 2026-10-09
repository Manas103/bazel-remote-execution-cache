package com.manas.reapi.server;

import com.manas.reapi.hash.Hashing;
import com.manas.reapi.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keyed on the hermetic action digest: same command + same input tree, same key. */
public final class ActionCacheServiceImpl extends ActionCacheGrpc.ActionCacheImplBase {
    private final Map<String, ActionResult> cache = new ConcurrentHashMap<>();

    @Override
    public void getActionResult(GetActionResultRequest request, StreamObserver<ActionResult> resp) {
        ActionResult cached = cache.get(Hashing.key(request.getActionDigest()));
        if (cached == null) {
            resp.onError(Status.NOT_FOUND.withDescription("no cached result for this action digest").asRuntimeException());
            return;
        }
        resp.onNext(cached.toBuilder().setCachedResult(true).build());
        resp.onCompleted();
    }

    @Override
    public void updateActionResult(UpdateActionResultRequest request, StreamObserver<ActionResult> resp) {
        ActionResult stored = request.getActionResult().toBuilder().setCachedResult(false).build();
        cache.put(Hashing.key(request.getActionDigest()), stored);
        resp.onNext(stored);
        resp.onCompleted();
    }

    public int size() {
        return cache.size();
    }

    /** Direct, in-process lookup used by ExecutionServiceImpl so a single simulated
     * worker does not have to make a second network round trip to its own cache. */
    public java.util.Optional<ActionResult> lookup(Digest actionDigest) {
        return java.util.Optional.ofNullable(cache.get(Hashing.key(actionDigest)));
    }

    public void store(Digest actionDigest, ActionResult result) {
        cache.put(Hashing.key(actionDigest), result.toBuilder().setCachedResult(false).build());
    }
}
