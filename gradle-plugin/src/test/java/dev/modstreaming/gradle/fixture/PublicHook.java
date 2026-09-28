package dev.modstreaming.gradle.fixture;

import dev.modstreaming.api.Bridge;

/** Public hook: only talks to the service through the Bridge. */
public final class PublicHook {
    public static String run(String input) {
        return Bridge.get(PublicService.class).map(s -> s.compute(input)).orElse("no-op");
    }
}
