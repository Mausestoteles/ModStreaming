package dev.modstreaming.core.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * AES-256-GCM with a random 96-bit nonce and a 128-bit tag.
 *
 * <p>Wire format: {@code nonce (12 bytes) || ciphertext || tag (16 bytes)}. The manifest bytes are
 * passed as additional authenticated data, which cryptographically binds the payload to its
 * metadata.</p>
 */
public final class AesGcm {

    public static final String ALGORITHM = "AES/GCM/NoPadding";
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    /** Generates a fresh 256-bit key from {@link SecureRandom}. */
    public static byte[] generateKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    public static byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        return encrypt(key, plaintext, aad, RANDOM);
    }

    public static byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad, SecureRandom random) {
        checkKey(key);
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[NONCE_BYTES + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(ct, 0, out, NONCE_BYTES, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts {@code payload} produced by {@link #encrypt}.
     *
     * @throws CryptoException if the key, nonce, tag or AAD do not match (tampering or wrong key)
     */
    public static byte[] decrypt(byte[] key, byte[] payload, byte[] aad) throws CryptoException {
        checkKey(key);
        Objects.requireNonNull(payload, "payload");
        if (payload.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new CryptoException("payload too short for AES-GCM");
        }
        byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES);
        } catch (AEADBadTagException e) {
            throw new CryptoException("AES-GCM authentication failed: payload was tampered with or the key is wrong");
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-GCM decryption failed: " + e.getClass().getSimpleName());
        }
    }

    /** Overwrites key material. */
    public static void wipe(byte[] key) {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static SecretKey secretKey(byte[] key) {
        return new SecretKeySpec(key, "AES");
    }

    private static void checkKey(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 key must be " + KEY_BYTES + " bytes, got " + key.length);
        }
    }
}
