package dev.modstreaming.core.crypto;

import dev.modstreaming.api.verify.SignatureVerifier;

import java.security.PublicKey;
import java.util.Objects;

/** {@link SignatureVerifier} backed by an Ed25519 public key. */
public final class Ed25519SignatureVerifier implements SignatureVerifier {

    private final PublicKey publicKey;

    public Ed25519SignatureVerifier(PublicKey publicKey) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    }

    public static Ed25519SignatureVerifier fromBase64(String publicKey) {
        return new Ed25519SignatureVerifier(Ed25519.decodePublicKey(publicKey));
    }

    @Override
    public boolean verify(byte[] data, byte[] signature) {
        return Ed25519.verify(publicKey, data, signature);
    }

    @Override
    public String algorithm() {
        return Ed25519.ALGORITHM;
    }

    public String fingerprint() {
        return Ed25519.fingerprint(publicKey);
    }
}
