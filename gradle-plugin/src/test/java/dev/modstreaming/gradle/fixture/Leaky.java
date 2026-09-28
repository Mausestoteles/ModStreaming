package dev.modstreaming.gradle.fixture;

import dev.modstreaming.gradle.fixture.secret.SecretImpl;

/** Public class that (wrongly) references a streamed class directly. */
public final class Leaky {
    public static String run(String input) {
        return new SecretImpl().compute(input);
    }
}
