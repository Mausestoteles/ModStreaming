package dev.modstreaming.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeTest {

    interface GreetingService {
        String greet(String name);
    }

    interface Unrelated {
    }

    @BeforeEach
    @AfterEach
    void reset() {
        Bridge.setProvider(new DefaultBridgeProvider());
    }

    @Test
    void hookIsNoOpWithoutRegisteredService() {
        List<String> calls = new ArrayList<>();
        Bridge.get(GreetingService.class).ifPresent(s -> calls.add(s.greet("Steve")));
        assertTrue(calls.isEmpty());
        assertFalse(Bridge.isAvailable(GreetingService.class));
        assertTrue(Bridge.services().isEmpty());
    }

    @Test
    void registeredServiceIsReturnedAndCanBeUnregistered() {
        GreetingService impl = name -> "Hello, " + name;
        Bridge.register(GreetingService.class, impl);

        Optional<GreetingService> found = Bridge.get(GreetingService.class);
        assertTrue(found.isPresent());
        assertSame(impl, found.get());
        assertEquals("Hello, Alex", found.get().greet("Alex"));
        assertTrue(Bridge.isAvailable(GreetingService.class));
        assertEquals(1, Bridge.services().size());

        assertTrue(Bridge.unregister(GreetingService.class));
        assertFalse(Bridge.unregister(GreetingService.class));
        assertFalse(Bridge.isAvailable(GreetingService.class));
    }

    @Test
    void registerRejectsWrongType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Object> raw = (Class) GreetingService.class;
        assertThrows(IllegalArgumentException.class, () -> Bridge.register(raw, "not a service"));
    }

    @Test
    void whenAvailableFiresOnceOnRegistration() {
        AtomicInteger fired = new AtomicInteger();
        Bridge.whenAvailable(GreetingService.class, s -> fired.incrementAndGet());
        assertEquals(0, fired.get());

        Bridge.register(GreetingService.class, name -> name);
        assertEquals(1, fired.get());

        Bridge.register(GreetingService.class, name -> name + "!");
        assertEquals(1, fired.get(), "callback must not fire again");

        Bridge.whenAvailable(GreetingService.class, s -> fired.incrementAndGet());
        assertEquals(2, fired.get(), "immediately fires when already available");
    }

    @Test
    void providerCanBeSwapped() {
        BridgeProvider custom = new DefaultBridgeProvider();
        Bridge.setProvider(custom);
        Bridge.register(Unrelated.class, new Unrelated() { });
        assertSame(custom, Bridge.provider());
        assertTrue(custom.get(Unrelated.class).isPresent());
        Bridge.clear();
        assertTrue(custom.services().isEmpty());
    }
}
