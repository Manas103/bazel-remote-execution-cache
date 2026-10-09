package com.manas.reapi.server;

import com.manas.reapi.cas.BlobStore;
import com.manas.reapi.proto.*;
import io.grpc.stub.StreamObserver;

public final class CasServiceImpl extends ContentAddressableStorageGrpc.ContentAddressableStorageImplBase {
    private final BlobStore store;

    public CasServiceImpl(BlobStore store) {
        this.store = store;
    }

    @Override
    public void findMissingBlobs(FindMissingBlobsRequest request, StreamObserver<FindMissingBlobsResponse> resp) {
        FindMissingBlobsResponse.Builder b = FindMissingBlobsResponse.newBuilder();
        for (Digest d : request.getBlobDigestsList()) {
            if (!store.has(d)) {
                b.addMissingBlobDigests(d);
            }
        }
        resp.onNext(b.build());
        resp.onCompleted();
    }

    @Override
    public void batchUpdateBlobs(BatchUpdateBlobsRequest request, StreamObserver<BatchUpdateBlobsResponse> resp) {
        BatchUpdateBlobsResponse.Builder b = BatchUpdateBlobsResponse.newBuilder();
        for (BatchUpdateBlobsRequest.Request r : request.getRequestsList()) {
            store.put(r.getDigest(), r.getData().toByteArray());
            b.addResponses(BatchUpdateBlobsResponse.Response.newBuilder()
                    .setDigest(r.getDigest())
                    .setStatusCode(0)
                    .build());
        }
        resp.onNext(b.build());
        resp.onCompleted();
    }

    @Override
    public void batchReadBlobs(BatchReadBlobsRequest request, StreamObserver<BatchReadBlobsResponse> resp) {
        BatchReadBlobsResponse.Builder b = BatchReadBlobsResponse.newBuilder();
        for (Digest d : request.getDigestsList()) {
            byte[] data = store.get(d);
            BatchReadBlobsResponse.Response.Builder rb = BatchReadBlobsResponse.Response.newBuilder().setDigest(d);
            if (data != null) {
                rb.setData(com.google.protobuf.ByteString.copyFrom(data)).setStatusCode(0);
            } else {
                rb.setStatusCode(5); // NOT_FOUND
            }
            b.addResponses(rb.build());
        }
        resp.onNext(b.build());
        resp.onCompleted();
    }
}
