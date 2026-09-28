package dev.modstreaming.api.verify;

/**
 * Verifies a detached signature over arbitrary bytes. The reference implementation uses Ed25519
 * with the public key embedded in the mod; other schemes can be plugged in.
 */
@FunctionalInterface
public interface SignatureVerifier {

    /** Returns {@code true} only if {@code signature} is valid for {@code data}. Never throws. */
    boolean verify(byte[] data, byte[] signature);

    /** Name of the signature algorithm, for diagnostics. */
    default String algorithm() {
        return "unknown";
    }
}
