package com.example.streamdemo.impl;

import com.example.streamdemo.api.GreetingService;
import dev.modstreaming.api.Streamed;
import net.minecraft.client.MinecraftClient;

/**
 * The valuable implementation. {@code @Streamed} removes this class (and its nested classes)
 * from the public jar; it only exists inside the encrypted artifact and is loaded in memory after
 * the backend authorized the player.
 */
@Streamed
public final class SecretGreetingService implements GreetingService {

    /** Nested helper: moved together with the outer class. */
    private static final class Scrambler {
        String apply(String input) {
            StringBuilder sb = new StringBuilder();
            for (char c : input.toCharArray()) {
                sb.append((char) (Character.isLetter(c) ? ((c ^ 0x20)) : c));
            }
            return sb.toString();
        }
    }

    @Override
    public String greet(String playerName) {
        String version = MinecraftClient.getInstance().getGameVersion();
        return "Welcome back, " + playerName + "! This greeting was streamed for Minecraft " + version
                + " (" + new Scrambler().apply(playerName) + ")";
    }
}
