package dev.modstreaming.fabric;

import dev.modstreaming.api.auth.CredentialsProvider;
import dev.modstreaming.core.runtime.LoadResult;

/**
 * Optional Fabric entrypoint ({@code "modstreaming"} in {@code fabric.mod.json}) a public mod can
 * implement to supply credentials and observe the outcome of streaming.
 *
 * <pre>{@code
 * "entrypoints": { "modstreaming": ["com.example.ExampleStreaming"] }
 * }</pre>
 *
 * <p>Every method has a default, so a mod may implement only what it needs. Exceptions thrown here
 * are caught and logged; they never crash the game.</p>
 */
public interface ModStreamingEntrypoint {

    /**
     * Credentials for the verification request, or {@code null} to use the default file based
     * provider ({@code config/modstreaming/<projectId>.json}).
     */
    default CredentialsProvider credentials() {
        return null;
    }

    /** Called once per load attempt with the terminal result (on the loader thread). */
    default void onResult(LoadResult result) {
    }
}
