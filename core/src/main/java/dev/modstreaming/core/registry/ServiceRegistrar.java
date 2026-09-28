package dev.modstreaming.core.registry;

import dev.modstreaming.api.BridgeProvider;
import dev.modstreaming.api.ServiceBinding;
import dev.modstreaming.core.loader.InMemoryClassLoader;
import dev.modstreaming.core.log.StreamLogger;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Instantiates streamed implementations and registers them in a {@link BridgeProvider}.
 *
 * <p>Safety rules enforced here: the service interface must come from the public side (the parent
 * class loader), the implementation must be defined by the in-memory loader, and it must actually
 * implement the interface. Violations are reported, never thrown into the game.</p>
 */
public final class ServiceRegistrar {

    /** One successful registration. */
    public record Registration(ServiceBinding binding, Class<?> service, Object instance) {
    }

    /** One binding that could not be registered. */
    public record Failure(ServiceBinding binding, String reason) {
    }

    public record Result(List<Registration> registered, List<Failure> failed) {
        public boolean allRegistered() {
            return failed.isEmpty();
        }
    }

    private final BridgeProvider bridge;
    private final StreamLogger log;

    public ServiceRegistrar(BridgeProvider bridge, StreamLogger log) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.log = Objects.requireNonNull(log, "log");
    }

    public Result register(List<ServiceBinding> bindings, InMemoryClassLoader loader) {
        List<Registration> registered = new ArrayList<>();
        List<Failure> failed = new ArrayList<>();
        Map<String, Object> instances = new HashMap<>();
        ClassLoader publicLoader = loader.getParent();

        for (ServiceBinding binding : bindings) {
            try {
                Class<?> service = Class.forName(binding.service(), false, publicLoader);
                if (service.getClassLoader() == loader) {
                    throw new IllegalStateException("service interface must be part of the public jar");
                }
                if (!loader.owns(binding.implementation())) {
                    throw new IllegalStateException("implementation is not contained in the streamed artifact");
                }
                Class<?> impl = Class.forName(binding.implementation(), true, loader);
                if (impl.getClassLoader() != loader) {
                    throw new IllegalStateException("implementation was not defined by the streamed class loader");
                }
                if (!service.isAssignableFrom(impl)) {
                    throw new IllegalStateException(impl.getName() + " does not implement " + service.getName());
                }
                Object instance = instances.get(impl.getName());
                if (instance == null) {
                    instance = instantiate(impl);
                    instances.put(impl.getName(), instance);
                }
                registerUnchecked(service, instance);
                registered.add(new Registration(binding, service, instance));
                log.info("registered " + service.getName() + " -> " + impl.getName());
            } catch (ClassNotFoundException e) {
                failed.add(new Failure(binding, "class not found: " + e.getMessage()));
                log.warn("cannot register " + binding.service() + ": class not found " + e.getMessage());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                String reason = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                failed.add(new Failure(binding, reason));
                log.error("cannot register " + binding.service() + " -> " + binding.implementation() + " (" + reason + ")", e);
            }
        }
        return new Result(List.copyOf(registered), List.copyOf(failed));
    }

    /** Removes every binding registered from {@code loader}. */
    public int unregister(List<Registration> registrations) {
        int count = 0;
        for (Registration r : registrations) {
            if (bridge.unregister(r.service())) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private void registerUnchecked(Class<?> service, Object instance) {
        bridge.register((Class<Object>) service, instance);
    }

    private static Object instantiate(Class<?> impl) throws ReflectiveOperationException {
        if (Modifier.isAbstract(impl.getModifiers()) || impl.isInterface()) {
            throw new IllegalStateException(impl.getName() + " is abstract");
        }
        // Prefer a public static INSTANCE field or a static create()/getInstance() factory.
        try {
            var field = impl.getDeclaredField("INSTANCE");
            if (Modifier.isStatic(field.getModifiers()) && impl.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) {
                    return value;
                }
            }
        } catch (NoSuchFieldException ignored) {
            // fall through
        }
        for (String factory : new String[] {"create", "getInstance"}) {
            try {
                var method = impl.getDeclaredMethod(factory);
                if (Modifier.isStatic(method.getModifiers()) && impl.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    Object value = method.invoke(null);
                    if (value != null) {
                        return value;
                    }
                }
            } catch (NoSuchMethodException ignored) {
                // fall through
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("factory " + factory + "() failed: " + e.getCause(), e.getCause());
            }
        }
        try {
            Constructor<?> ctor = impl.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(impl.getName() + " needs a no-arg constructor, a static INSTANCE field or a static create() method");
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("constructor of " + impl.getName() + " failed: " + e.getCause(), e.getCause());
        }
    }
}
