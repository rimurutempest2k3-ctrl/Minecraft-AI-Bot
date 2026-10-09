package minecraftaibot.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import minecraftaibot.client.MinecraftAIBotClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Avoid both unlimited frame spinning and inactive-window throttling in console mode. */
@Mixin(FramerateLimitTracker.class)
public class ConsoleFrameLimitMixin {
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void consoleLoopLimit(CallbackInfoReturnable<Integer> info) {
        if (MinecraftAIBotClient.consoleOnly()) info.setReturnValue(30);
    }
}
