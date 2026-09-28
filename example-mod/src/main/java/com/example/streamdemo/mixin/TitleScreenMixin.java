package com.example.streamdemo.mixin;

import com.example.streamdemo.hook.GreetingHook;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mixins stay local: they only call the public hook. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void streamdemo$onInit(CallbackInfo ci) {
        GreetingHook.onTitleScreen();
    }
}
