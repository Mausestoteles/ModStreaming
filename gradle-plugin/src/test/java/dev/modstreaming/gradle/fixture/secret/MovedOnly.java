package dev.modstreaming.gradle.fixture.secret;

import dev.modstreaming.api.Streamed;

@Streamed(register = false)
public final class MovedOnly implements Runnable {
    @Override
    public void run() {
    }
}
