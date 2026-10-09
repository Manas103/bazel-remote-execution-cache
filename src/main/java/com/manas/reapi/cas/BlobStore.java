package com.manas.reapi.cas;

import com.manas.reapi.hash.Hashing;
import com.manas.reapi.proto.Digest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The content-addressable store itself: one blob per distinct SHA-256
 * hash, regardless of how many different actions or revisions reference
 * it. referencedBytes counts every upload attempt including ones that
 * turned out to already be present; storedBytes counts only the first.
 * referencedBytes / storedBytes is this project's CAS deduplication
 * ratio.
 */
public final class BlobStore {
    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    private final AtomicLong storedBytes = new AtomicLong();
    private final AtomicLong referencedBytes = new AtomicLong();
    private final AtomicLong putCalls = new AtomicLong();
    private final AtomicLong newBlobs = new AtomicLong();

    /** Returns true if this call actually stored a new blob (a cache miss at the blob level). */
    public boolean put(Digest digest, byte[] data) {
        putCalls.incrementAndGet();
        referencedBytes.addAndGet(data.length);
        String key = Hashing.key(digest);
        byte[] prior = blobs.putIfAbsent(key, data);
        if (prior == null) {
            storedBytes.addAndGet(data.length);
            newBlobs.incrementAndGet();
            return true;
        }
        return false;
    }

    public byte[] get(Digest digest) {
        return blobs.get(Hashing.key(digest));
    }

    public boolean has(Digest digest) {
        return blobs.containsKey(Hashing.key(digest));
    }

    public long storedBytes() {
        return storedBytes.get();
    }

    public long referencedBytes() {
        return referencedBytes.get();
    }

    public long putCalls() {
        return putCalls.get();
    }

    public long newBlobs() {
        return newBlobs.get();
    }

    public double deduplicationRatio() {
        long stored = storedBytes.get();
        if (stored == 0) return 0;
        return (double) referencedBytes.get() / (double) stored;
    }
}
