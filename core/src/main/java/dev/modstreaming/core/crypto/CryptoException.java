package dev.modstreaming.core.crypto;

/** Thrown when signature verification, decryption or key decoding fails. Messages never contain key material. */
public class CryptoException extends Exception {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
