package com.manas.reapi.hash;

import com.manas.reapi.proto.Digest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 content addressing: the same bytes always hash to the same Digest. */
public final class Hashing {
    private Hashing() {}

    public static Digest digestOf(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return Digest.newBuilder().setHash(sb.toString()).setSizeBytes(data.length).build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String key(Digest d) {
        return d.getHash() + ":" + d.getSizeBytes();
    }
}
