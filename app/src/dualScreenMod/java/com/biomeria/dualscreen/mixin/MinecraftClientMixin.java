package com.biomeria.dualscreen.mixin;

import com.biomeria.dualscreen.GamePanelBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.class_310", remap = false)
abstract class MinecraftClientMixin {
    @Inject(method = "method_1574()V", at = @At("TAIL"), remap = false, require = 0)
    private void biomeria$syncPanel(CallbackInfo callback) {
        GamePanelBridge.tick(this);
    }
}
