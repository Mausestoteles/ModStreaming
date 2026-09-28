package dev.modstreaming.core.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmTest {

    private static final byte[] AAD = "manifest".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTrip() throws Exception {
        byte[] key = AesGcm.generateKey();
        byte[] plaintext = "streamed jar bytes".getBytes(StandardCharsets.UTF_8);
        byte[] payload = AesGcm.encrypt(key, plaintext, AAD);
        assertEquals(AesGcm.NONCE_BYTES + plaintext.length + AesGcm.TAG_BITS / 8, payload.length);
        assertArrayEquals(plaintext, AesGcm.decrypt(key, payload, AAD));
    }

    @Test
    void keyIs256BitsAndRandom() {
        byte[] a = AesGcm.generateKey();
        byte[] b = AesGcm.generateKey();
        assertEquals(32, a.length);
        assertFalse(Arrays.equals(a, b));
    }

    @Test
    void noncesAreUniquePerEncryption() {
        byte[] key = AesGcm.generateKey();
        byte[] p1 = AesGcm.encrypt(key, new byte[16], AAD);
        byte[] p2 = AesGcm.encrypt(key, new byte[16], AAD);
        assertFalse(Arrays.equals(Arrays.copyOf(p1, 12), Arrays.copyOf(p2, 12)));
        assertFalse(Arrays.equals(p1, p2));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] key = AesGcm.generateKey();
        byte[] payload = AesGcm.encrypt(key, "secret".getBytes(StandardCharsets.UTF_8), AAD);
        payload[AesGcm.NONCE_BYTES] ^= 0x01;
        CryptoException e = assertThrows(CryptoException.class, () -> AesGcm.decrypt(key, payload, AAD));
        assertTrue(e.getMessage().contains("authentication failed"));
    }

    @Test
    void tamperedTagIsRejected() {
        byte[] key = AesGcm.generateKey();
        byte[] payload = AesGcm.encrypt(key, "secret".getBytes(StandardCharsets.UTF_8), AAD);
        payload[payload.length - 1] ^= 0x80;
        assertThrows(CryptoException.class, () -> AesGcm.decrypt(key, payload, AAD));
    }

    @Test
    void wrongKeyOrAadIsRejected() {
        byte[] key = AesGcm.generateKey();
        byte[] payload = AesGcm.encrypt(key, "secret".getBytes(StandardCharsets.UTF_8), AAD);
        assertThrows(CryptoException.class, () -> AesGcm.decrypt(AesGcm.generateKey(), payload, AAD));
        assertThrows(CryptoException.class, () -> AesGcm.decrypt(key, payload, "other".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsBadKeySizeAndShortPayload() {
        assertThrows(IllegalArgumentException.class, () -> AesGcm.encrypt(new byte[16], new byte[1], AAD));
        assertThrows(CryptoException.class, () -> AesGcm.decrypt(AesGcm.generateKey(), new byte[5], AAD));
    }

    @Test
    void wipeZeroesKey() {
        byte[] key = AesGcm.generateKey();
        AesGcm.wipe(key);
        assertArrayEquals(new byte[32], key);
    }
}
