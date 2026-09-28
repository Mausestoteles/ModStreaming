package dev.modstreaming.fabric;

import dev.modstreaming.api.auth.CredentialsProvider;
import dev.modstreaming.core.config.StreamConfig;

import java.util.List;
import java.util.Objects;

/**
 * One public mod that ships an embedded stream config.
 *
 * @param modId       Fabric mod id
 * @param config      the embedded, public configuration
 * @param credentials credentials source for the verification request
 * @param entrypoints the mod's {@code modstreaming} entrypoints (may be empty)
 */
public record StreamedMod(String modId, StreamConfig config, CredentialsProvider credentials,
                          List<ModStreamingEntrypoint> entrypoints) {

    public StreamedMod {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(config, "config");
        credentials = credentials == null ? CredentialsProvider.none() : credentials;
        entrypoints = entrypoints == null ? List.of() : List.copyOf(entrypoints);
    }

    public String projectId() {
        return config.projectId();
    }
}
