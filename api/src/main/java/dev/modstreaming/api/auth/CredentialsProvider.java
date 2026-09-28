package dev.modstreaming.api.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Supplies the generic credential structure sent with a verification request.
 *
 * <p>ModStreaming does not prescribe an authentication method. The map may carry a license key,
 * an account token, a Minecraft UUID, a Discord identity, a device id, a website session or any
 * combination; its meaning is defined entirely by the backend operator's
 * {@code AuthorizationProvider}. Credentials are treated as sensitive and are never logged.</p>
 */
@FunctionalInterface
public interface CredentialsProvider {

    /** Credentials to include in the next verification request. May be empty, never {@code null}. */
    Map<String, String> credentials();

    /** A provider that sends no credentials. */
    static CredentialsProvider none() {
        return Collections::emptyMap;
    }

    /** A provider returning a fixed, defensively copied map. */
    static CredentialsProvider of(Map<String, String> credentials) {
        Objects.requireNonNull(credentials, "credentials");
        Map<String, String> copy = Collections.unmodifiableMap(new LinkedHashMap<>(credentials));
        return () -> copy;
    }

    /** Convenience for a single credential field. */
    static CredentialsProvider of(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return of(Map.of(key, value));
    }
}
