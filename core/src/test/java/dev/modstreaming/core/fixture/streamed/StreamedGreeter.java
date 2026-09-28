package dev.modstreaming.core.fixture.streamed;

import dev.modstreaming.api.Streamed;
import dev.modstreaming.core.fixture.FixtureService;

import java.util.function.IntSupplier;

/** Fixture implementation that is packaged into test artifacts. Has a nested and an anonymous class. */
@Streamed
public final class StreamedGreeter implements FixtureService {

    public static final class Inner {
        int value() {
            return 42;
        }
    }

    @Override
    public String greet(String name) {
        return "Hello from the stream, " + name + "!";
    }

    @Override
    public int innerValue() {
        IntSupplier supplier = new IntSupplier() {
            @Override
            public int getAsInt() {
                return new Inner().value();
            }
        };
        return supplier.getAsInt();
    }
}
