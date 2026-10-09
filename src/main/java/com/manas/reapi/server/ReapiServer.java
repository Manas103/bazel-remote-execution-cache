package com.manas.reapi.server;

import com.manas.reapi.cas.BlobStore;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;

/** Bootstraps the three REAPI services (CAS, ActionCache, Execution) on one real gRPC port. */
public final class ReapiServer {
    private final Server server;
    private final BlobStore blobStore = new BlobStore();
    private final ActionCacheServiceImpl actionCache = new ActionCacheServiceImpl();

    public ReapiServer(int port) {
        this.server = ServerBuilder.forPort(port)
                .addService(new CasServiceImpl(blobStore))
                .addService(actionCache)
                .addService(new ExecutionServiceImpl(actionCache, blobStore))
                .build();
    }

    public void start() throws IOException {
        server.start();
    }

    public void stop() {
        server.shutdownNow();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    public int port() {
        return server.getPort();
    }

    public BlobStore blobStore() {
        return blobStore;
    }

    public ActionCacheServiceImpl actionCache() {
        return actionCache;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 50051;
        ReapiServer server = new ReapiServer(port);
        server.start();
        System.out.println("REAPI server listening on port " + server.port());
        server.awaitTermination();
    }
}
