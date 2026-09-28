package dev.modstreaming.gradle.fixture.secret;

import dev.modstreaming.api.Streamed;
import dev.modstreaming.gradle.fixture.PublicService;

@Streamed(PublicService.class)
public final class ExplicitImpl implements PublicService, Runnable {
    @Override
    public String compute(String input) {
        return input;
    }

    @Override
    public void run() {
    }
}
