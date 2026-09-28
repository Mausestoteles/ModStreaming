package dev.modstreaming.api;

import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Backing registry of the {@link Bridge}. The default implementation is an in-memory map;
 * loaders or tests may install their own via {@link Bridge#setProvider(BridgeProvider)}.
 */
public interface BridgeProvider {

    /** Returns the registered implementation for {@code service}, if any. */
    <T> Optional<T> get(Class<T> service);

    /** Registers {@code implementation} for {@code service}, replacing any previous binding. */
    <T> void register(Class<T> service, T implementation);

    /** Removes the binding for {@code service}. Returns {@code true} if something was removed. */
    boolean unregister(Class<?> service);

    /** All currently bound service interfaces. */
    Set<Class<?>> services();

    /**
     * Runs {@code callback} once, as soon as an implementation of {@code service} is available.
     * If one is already registered the callback runs immediately on the calling thread.
     */
    <T> void whenAvailable(Class<T> service, Consumer<? super T> callback);

    /** Removes all bindings and pending callbacks. */
    void clear();
}
