package dev.modstreaming.core.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. */
public final class Sha256 {

    private Sha256() {
    }

    public static byte[] digest(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String hex(byte[] data) {
        return HexFormat.of().formatHex(digest(data));
    }

    /** Constant-time comparison of two hex digests. */
    public static boolean hexEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                b.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
