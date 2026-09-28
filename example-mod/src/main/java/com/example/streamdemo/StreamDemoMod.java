package com.example.streamdemo;

import com.example.streamdemo.api.GreetingService;
import dev.modstreaming.api.Bridge;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StreamDemoMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("streamdemo");

    @Override
    public void onInitializeClient() {
        LOGGER.info("streamdemo initialized; waiting for the streamed GreetingService");
        // Fires once the artifact has been verified, decrypted and registered (or immediately if already there).
        Bridge.whenAvailable(GreetingService.class, service -> LOGGER.info("GreetingService is now available: {}",
                service.getClass().getName()));
    }
}
