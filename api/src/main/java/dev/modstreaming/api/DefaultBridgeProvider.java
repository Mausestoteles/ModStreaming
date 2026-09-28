package dev.modstreaming.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe in-memory {@link BridgeProvider}.
 */
public final class DefaultBridgeProvider implements BridgeProvider {

    private final Map<Class<?>, Object> bindings = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<Consumer<Object>>> pending = new ConcurrentHashMap<>();

    @Override
    public <T> Optional<T> get(Class<T> service) {
        Objects.requireNonNull(service, "service");
        Object bound = bindings.get(service);
        return bound == null ? Optional.empty() : Optional.of(service.cast(bound));
    }

    @Override
    public <T> void register(Class<T> service, T implementation) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(implementation, "implementation");
        if (!service.isInstance(implementation)) {
            throw new IllegalArgumentException(implementation.getClass().getName()
                    + " does not implement " + service.getName());
        }
        bindings.put(service, implementation);
        List<Consumer<Object>> callbacks = pending.remove(service);
        if (callbacks != null) {
            for (Consumer<Object> callback : callbacks) {
                callback.accept(implementation);
            }
        }
    }

    @Override
    public boolean unregister(Class<?> service) {
        Objects.requireNonNull(service, "service");
        return bindings.remove(service) != null;
    }

    @Override
    public Set<Class<?>> services() {
        return Collections.unmodifiableSet(new java.util.HashSet<>(bindings.keySet()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void whenAvailable(Class<T> service, Consumer<? super T> callback) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(callback, "callback");
        Object bound = bindings.get(service);
        if (bound != null) {
            callback.accept(service.cast(bound));
            return;
        }
        pending.computeIfAbsent(service, k -> new CopyOnWriteArrayList<>())
                .add(o -> ((Consumer<Object>) callback).accept(o));
        // Registration may have raced with us: deliver if it is bound by now.
        Object late = bindings.get(service);
        if (late != null) {
            List<Consumer<Object>> callbacks = pending.remove(service);
            if (callbacks != null) {
                for (Consumer<Object> c : new ArrayList<>(callbacks)) {
                    c.accept(late);
                }
            }
        }
    }

    @Override
    public void clear() {
        bindings.clear();
        pending.clear();
    }
}
