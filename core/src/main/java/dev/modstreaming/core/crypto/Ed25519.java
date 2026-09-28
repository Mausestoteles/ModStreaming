package dev.modstreaming.core.crypto;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Ed25519 signing and verification (JDK built-in provider, Java 15+).
 *
 * <p>Keys are exchanged as Base64. Public keys may be the raw 32 key bytes or a DER encoded
 * X.509 {@code SubjectPublicKeyInfo}; private keys the raw 32 byte seed or a DER encoded PKCS#8
 * structure. Files written by the Gradle plugin use the raw form.</p>
 */
public final class Ed25519 {

    public static final String ALGORITHM = "Ed25519";
    public static final int RAW_KEY_BYTES = 32;
    public static final int SIGNATURE_BYTES = 64;

    /** DER prefix of an Ed25519 SubjectPublicKeyInfo (RFC 8410). */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    /** DER prefix of an Ed25519 PKCS#8 PrivateKeyInfo (RFC 8410). */
    private static final byte[] PKCS8_PREFIX = HexFormat.of().parseHex("302e020100300506032b657004220420");

    private Ed25519() {
    }

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 not available", e);
        }
    }

    public static byte[] sign(PrivateKey privateKey, byte[] data) {
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(data, "data");
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /** Returns {@code true} only for a valid signature. Never throws. */
    public static boolean verify(PublicKey publicKey, byte[] data, byte[] signature) {
        if (publicKey == null || data == null || signature == null || signature.length != SIGNATURE_BYTES) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (SignatureException | InvalidKeyException e) {
            return false;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    // ---- encoding -------------------------------------------------------------------------

    /** Raw 32 public key bytes. */
    public static byte[] rawPublicKey(PublicKey key) {
        byte[] encoded = key.getEncoded();
        if (encoded.length == SPKI_PREFIX.length + RAW_KEY_BYTES && startsWith(encoded, SPKI_PREFIX)) {
            return Arrays.copyOfRange(encoded, SPKI_PREFIX.length, encoded.length);
        }
        throw new IllegalArgumentException("unexpected Ed25519 public key encoding (" + encoded.length + " bytes)");
    }

    /** Raw 32 byte private seed. */
    public static byte[] rawPrivateKey(PrivateKey key) {
        byte[] encoded = key.getEncoded();
        if (encoded.length == PKCS8_PREFIX.length + RAW_KEY_BYTES && startsWith(encoded, PKCS8_PREFIX)) {
            return Arrays.copyOfRange(encoded, PKCS8_PREFIX.length, encoded.length);
        }
        throw new IllegalArgumentException("unexpected Ed25519 private key encoding (" + encoded.length + " bytes)");
    }

    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(rawPublicKey(key));
    }

    public static String encodePrivateKey(PrivateKey key) {
        return Base64.getEncoder().encodeToString(rawPrivateKey(key));
    }

    public static PublicKey decodePublicKey(String base64) {
        return decodePublicKey(decodeBase64(base64, "public key"));
    }

    public static PublicKey decodePublicKey(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        byte[] der;
        if (encoded.length == RAW_KEY_BYTES) {
            der = concat(SPKI_PREFIX, encoded);
        } else if (encoded.length == SPKI_PREFIX.length + RAW_KEY_BYTES) {
            der = encoded;
        } else {
            throw new IllegalArgumentException("Ed25519 public key must be 32 raw bytes or a 44 byte SubjectPublicKeyInfo, got " + encoded.length);
        }
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid Ed25519 public key", e);
        }
    }

    public static PrivateKey decodePrivateKey(String base64) {
        return decodePrivateKey(decodeBase64(base64, "private key"));
    }

    public static PrivateKey decodePrivateKey(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        byte[] der;
        if (encoded.length == RAW_KEY_BYTES) {
            der = concat(PKCS8_PREFIX, encoded);
        } else if (encoded.length == PKCS8_PREFIX.length + RAW_KEY_BYTES) {
            der = encoded;
        } else {
            throw new IllegalArgumentException("Ed25519 private key must be 32 raw bytes or a 48 byte PKCS#8 structure, got " + encoded.length);
        }
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid Ed25519 private key", e);
        }
    }

    /** Lower-case hex SHA-256 of the raw public key; safe to log and to embed in manifests. */
    public static String fingerprint(PublicKey key) {
        return Sha256.hex(rawPublicKey(key));
    }

    private static byte[] decodeBase64(String value, String what) {
        Objects.requireNonNull(value, what);
        String trimmed = value.trim();
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getUrlDecoder().decode(trimmed);
            } catch (IllegalArgumentException e2) {
                throw new IllegalArgumentException(what + " is not valid Base64");
            }
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
