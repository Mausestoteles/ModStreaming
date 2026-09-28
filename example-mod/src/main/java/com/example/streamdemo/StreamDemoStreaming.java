package com.example.streamdemo;

import dev.modstreaming.api.auth.CredentialsProvider;
import dev.modstreaming.core.runtime.LoadResult;
import dev.modstreaming.fabric.ModStreamingEntrypoint;

/**
 * Optional {@code modstreaming} entrypoint. Returning {@code null} from {@link #credentials()}
 * keeps the default file based provider ({@code config/modstreaming/example-mod.json}); a mod
 * could instead return its own account token, a Discord identity, a device id, ...
 */
public final class StreamDemoStreaming implements ModStreamingEntrypoint {

    @Override
    public CredentialsProvider credentials() {
        return null;
    }

    @Override
    public void onResult(LoadResult result) {
        switch (result.status()) {
            case LOADED -> StreamDemoMod.LOGGER.info("streamed logic loaded: {}", result.services());
            case DENIED -> StreamDemoMod.LOGGER.warn("backend denied streaming ({}); running without streamed features", result.message());
            default -> StreamDemoMod.LOGGER.warn("streaming unavailable: {}", result.message());
        }
    }
}
