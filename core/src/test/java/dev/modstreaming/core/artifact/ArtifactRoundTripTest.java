package dev.modstreaming.core.artifact;

import dev.modstreaming.api.auth.ArtifactCoordinates;
import dev.modstreaming.core.crypto.AesGcm;
import dev.modstreaming.core.crypto.Ed25519;
import dev.modstreaming.core.testutil.TestArtifacts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactRoundTripTest {

    private static KeyPair keys;
    private static byte[] jar;
    private static ArtifactPackager.Packaged packaged;

    @BeforeAll
    static void setUp() throws Exception {
        keys = Ed25519.generateKeyPair();
        jar = TestArtifacts.streamedJar();
        packaged = TestArtifacts.packaged(keys);
    }

    @Test
    void packageVerifyDecrypt() throws Exception {
        byte[] bytes = packaged.containerBytes();
        ArtifactContainer container = ArtifactContainer.fromBytes(bytes);
        VerifiedArtifact verified = ArtifactVerifier.forPublicKey(keys.getPublic()).verify(container, TestArtifacts.COORDINATES);

        ArtifactManifest manifest = verified.manifest();
        assertEquals(TestArtifacts.COORDINATES, manifest.coordinates());
        assertEquals(TestArtifacts.BINDINGS, manifest.services());
        assertEquals(jar.length, manifest.contentSize());
        assertEquals(Ed25519.fingerprint(keys.getPublic()), manifest.publicKeyFingerprint());
        assertEquals(1, manifest.formatVersion());

        byte[] plaintext = verified.decrypt(packaged.key());
        assertArrayEquals(jar, plaintext);
    }

    @Test
    void manifestJsonRoundTrips() throws Exception {
        ArtifactManifest parsed = ArtifactManifest.fromJson(packaged.manifest().toJson());
        assertEquals(packaged.manifest(), parsed);
        assertTrue(packaged.manifest().toJson().contains("\"algorithm\": \"AES/GCM/NoPadding\""));
        assertTrue(packaged.manifest().toJson().contains("\"algorithm\": \"Ed25519\""));
    }

    @Test
    void tamperedPayloadIsRejectedBeforeDecryption() throws Exception {
        byte[] payload = packaged.container().payload().clone();
        payload[payload.length / 2] ^= 0x55;
        ArtifactContainer tampered = new ArtifactContainer(packaged.container().manifestBytes(), packaged.container().signature(), payload);
        ArtifactContainer reread = ArtifactContainer.fromBytes(tampered.toBytes());
        ArtifactException e = assertThrows(ArtifactException.class,
                () -> ArtifactVerifier.forPublicKey(keys.getPublic()).verify(reread, TestArtifacts.COORDINATES));
        assertTrue(e.getMessage().contains("payload hash"), e.getMessage());
    }

    @Test
    void tamperedManifestBreaksSignature() throws Exception {
        String manifest = new String(packaged.container().manifestBytes(), StandardCharsets.UTF_8)
                .replace("\"modVersion\": \"1.2.3\"", "\"modVersion\": \"9.9.9\"");
        ArtifactContainer tampered = new ArtifactContainer(manifest.getBytes(StandardCharsets.UTF_8),
                packaged.container().signature(), packaged.container().payload());
        ArtifactException e = assertThrows(ArtifactException.class,
                () -> ArtifactVerifier.forPublicKey(keys.getPublic()).verify(tampered, TestArtifacts.COORDINATES));
        assertTrue(e.getMessage().contains("signature is invalid"), e.getMessage());
    }

    @Test
    void artifactSignedWithAnotherKeyIsRejected() throws Exception {
        KeyPair other = Ed25519.generateKeyPair();
        ArtifactPackager.Packaged foreign = new ArtifactPackager().pack(jar, TestArtifacts.COORDINATES,
                TestArtifacts.BINDINGS, other.getPrivate(), other.getPublic());
        assertThrows(ArtifactException.class,
                () -> ArtifactVerifier.forPublicKey(keys.getPublic()).verify(foreign.container(), TestArtifacts.COORDINATES));
    }

    @Test
    void mismatchedCoordinatesAreRejected() {
        ArtifactCoordinates otherProject = new ArtifactCoordinates("other-mod", "1.2.3", "1.21.1", "fabric");
        ArtifactCoordinates otherVersion = new ArtifactCoordinates("test-mod", "1.2.4", "1.21.1", "fabric");
        ArtifactCoordinates otherLoader = new ArtifactCoordinates("test-mod", "1.2.3", "1.21.1", "neoforge");
        ArtifactVerifier verifier = ArtifactVerifier.forPublicKey(keys.getPublic());
        for (ArtifactCoordinates c : new ArtifactCoordinates[] {otherProject, otherVersion, otherLoader}) {
            assertThrows(ArtifactException.class, () -> verifier.verify(packaged.container(), c), c.key());
        }
    }

    @Test
    void wrongKeyFailsDecryption() throws Exception {
        VerifiedArtifact verified = ArtifactVerifier.forPublicKey(keys.getPublic()).verify(packaged.container(), TestArtifacts.COORDINATES);
        assertThrows(ArtifactException.class, () -> verified.decrypt(AesGcm.generateKey()));
        assertThrows(ArtifactException.class, () -> verified.decrypt(new byte[16]));
    }

    @Test
    void validatorCanRejectArtifacts() throws Exception {
        ArtifactVerifier verifier = ArtifactVerifier.forPublicKey(keys.getPublic(),
                manifest -> {
                    throw new IllegalStateException("build too old");
                });
        ArtifactException e = assertThrows(ArtifactException.class,
                () -> verifier.verify(packaged.container(), TestArtifacts.COORDINATES));
        assertTrue(e.getMessage().contains("build too old"));
    }

    @Test
    void malformedContainersAreRejected() {
        assertThrows(ArtifactException.class, () -> ArtifactContainer.fromBytes(new byte[] {1, 2, 3}));
        byte[] bytes = packaged.containerBytes();
        assertThrows(ArtifactException.class, () -> ArtifactContainer.fromBytes(Arrays.copyOf(bytes, bytes.length / 2)));
        assertThrows(ArtifactException.class, () -> ArtifactContainer.fromBytes(bytes, 16));
        assertThrows(ArtifactException.class, () -> new ArtifactContainer(new byte[] {'{', '}'}, new byte[64], new byte[40]).parseManifest());
    }

    @Test
    void packagerRejectsMismatchedKeyPair() {
        KeyPair other = Ed25519.generateKeyPair();
        assertThrows(IllegalArgumentException.class, () -> new ArtifactPackager().pack(jar, TestArtifacts.COORDINATES,
                TestArtifacts.BINDINGS, keys.getPrivate(), other.getPublic()));
    }
}
