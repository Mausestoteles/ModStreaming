package dev.modstreaming.core.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ed25519Test {

    private static final byte[] DATA = "manifest bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void signAndVerify() {
        KeyPair keys = Ed25519.generateKeyPair();
        byte[] signature = Ed25519.sign(keys.getPrivate(), DATA);
        assertEquals(64, signature.length);
        assertTrue(Ed25519.verify(keys.getPublic(), DATA, signature));
    }

    @Test
    void tamperedDataFails() {
        KeyPair keys = Ed25519.generateKeyPair();
        byte[] signature = Ed25519.sign(keys.getPrivate(), DATA);
        byte[] tampered = DATA.clone();
        tampered[0] ^= 1;
        assertFalse(Ed25519.verify(keys.getPublic(), tampered, signature));
        byte[] badSignature = signature.clone();
        badSignature[10] ^= 1;
        assertFalse(Ed25519.verify(keys.getPublic(), DATA, badSignature));
        assertFalse(Ed25519.verify(keys.getPublic(), DATA, new byte[10]));
        assertFalse(Ed25519.verify(keys.getPublic(), DATA, null));
    }

    @Test
    void wrongKeyFails() {
        KeyPair keys = Ed25519.generateKeyPair();
        KeyPair other = Ed25519.generateKeyPair();
        byte[] signature = Ed25519.sign(keys.getPrivate(), DATA);
        assertFalse(Ed25519.verify(other.getPublic(), DATA, signature));
    }

    @Test
    void rawAndDerEncodingsRoundTrip() {
        KeyPair keys = Ed25519.generateKeyPair();
        String rawPub = Ed25519.encodePublicKey(keys.getPublic());
        String rawPriv = Ed25519.encodePrivateKey(keys.getPrivate());
        assertEquals(32, Base64.getDecoder().decode(rawPub).length);
        assertEquals(32, Base64.getDecoder().decode(rawPriv).length);

        PublicKey pubFromRaw = Ed25519.decodePublicKey(rawPub);
        PublicKey pubFromDer = Ed25519.decodePublicKey(keys.getPublic().getEncoded());
        PrivateKey privFromRaw = Ed25519.decodePrivateKey(rawPriv);
        PrivateKey privFromDer = Ed25519.decodePrivateKey(keys.getPrivate().getEncoded());
        assertArrayEquals(keys.getPublic().getEncoded(), pubFromRaw.getEncoded());
        assertArrayEquals(keys.getPublic().getEncoded(), pubFromDer.getEncoded());
        assertArrayEquals(keys.getPrivate().getEncoded(), privFromRaw.getEncoded());
        assertArrayEquals(keys.getPrivate().getEncoded(), privFromDer.getEncoded());

        byte[] signature = Ed25519.sign(privFromRaw, DATA);
        assertTrue(Ed25519.verify(pubFromRaw, DATA, signature));
        assertEquals(64, Ed25519.fingerprint(keys.getPublic()).length());
        assertEquals(Ed25519.fingerprint(keys.getPublic()), Ed25519.fingerprint(pubFromRaw));
    }

    @Test
    void malformedKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Ed25519.decodePublicKey("not base64!!"));
        assertThrows(IllegalArgumentException.class, () -> Ed25519.decodePublicKey(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> Ed25519.decodePrivateKey(new byte[33]));
    }
}
