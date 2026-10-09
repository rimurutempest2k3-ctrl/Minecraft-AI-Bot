package minecraftaibot.client.mixin;

import minecraftaibot.client.MinecraftAIBotClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skip scene/UI extraction and drawing, preserving the client's tick and GPU task loop. */
@Mixin(GameRenderer.class)
public class ConsoleRenderMixin {
    @Inject(method = {"extract", "render"}, at = @At("HEAD"), cancellable = true)
    private void skipDrawing(CallbackInfo info) {
        // Resource-loading overlays must finish normally, even with the window hidden.
        if (MinecraftAIBotClient.skipDisplayRendering()) info.cancel();
    }
}
