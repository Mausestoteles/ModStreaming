package com.example.streamdemo.hook;

import com.example.streamdemo.api.GreetingService;
import dev.modstreaming.api.Bridge;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hook called from the {@code TitleScreen} mixin. Without a loaded artifact it does nothing.
 */
public final class GreetingHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("streamdemo");

    //? if >=1.21.4 {
    /*private static final String TARGET = "1.21.4 or newer";*/
    //?} else {
    private static final String TARGET = "1.21.1";
    //?}

    private GreetingHook() {
    }

    public static void onTitleScreen() {
        Bridge.get(GreetingService.class).ifPresent(service -> {
            String name = MinecraftClient.getInstance().getSession().getUsername();
            LOGGER.info("[{}] {}", TARGET, service.greet(name));
        });
    }
}
