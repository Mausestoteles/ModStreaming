package com.example.streamdemo.api;

/**
 * Public service interface. Stays in the public jar; the implementation
 * ({@code com.example.streamdemo.impl.SecretGreetingService}) is streamed at runtime.
 */
public interface GreetingService {

    /** Builds the greeting shown when the title screen opens. */
    String greet(String playerName);
}
