package dev.modstreaming.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Static access point between the public mod (hooks, mixins) and streamed implementations.
 *
 * <p>Hooks in the public jar never reference streamed classes directly. They ask the bridge for
 * a service interface and do nothing if it is not (yet) available:</p>
 *
 * <pre>{@code
 * Bridge.get(GreetingService.class).ifPresent(service -> service.greet(player));
 * }</pre>
 *
 * <p>Streamed implementations are registered by the ModStreaming runtime after the artifact has
 * been verified, decrypted and loaded. Without a loaded artifact every hook is a no-op.</p>
 */
public final class Bridge {

    private static volatile BridgeProvider provider = new DefaultBridgeProvider();

    private Bridge() {
    }

    /** Returns the implementation registered for {@code service}, or empty if not loaded. */
    public static <T> Optional<T> get(Class<T> service) {
        return provider.get(service);
    }

    /** Returns {@code true} if an implementation of {@code service} is currently registered. */
    public static boolean isAvailable(Class<?> service) {
        return provider.get(service).isPresent();
    }

    /** Registers {@code implementation} for {@code service}. Usually called by the runtime. */
    public static <T> void register(Class<T> service, T implementation) {
        provider.register(service, implementation);
    }

    /** Removes the binding for {@code service}. */
    public static boolean unregister(Class<?> service) {
        return provider.unregister(service);
    }

    /** All service interfaces with a registered implementation. */
    public static Set<Class<?>> services() {
        return provider.services();
    }

    /**
     * Runs {@code callback} once an implementation of {@code service} becomes available
     * (immediately if it already is).
     */
    public static <T> void whenAvailable(Class<T> service, Consumer<? super T> callback) {
        provider.whenAvailable(service, callback);
    }

    /** Replaces the backing provider. Intended for loaders and tests. */
    public static void setProvider(BridgeProvider newProvider) {
        provider = Objects.requireNonNull(newProvider, "provider");
    }

    /** The current backing provider. */
    public static BridgeProvider provider() {
        return provider;
    }

    /** Removes every binding. Intended for tests and artifact unloading. */
    public static void clear() {
        provider.clear();
    }
}
