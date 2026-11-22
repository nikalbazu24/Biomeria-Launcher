package com.prissma.dualscreen.mixin;

import com.prissma.dualscreen.GamePanelBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.class_437", remap = false)
abstract class CraftingScreenMixin {
    @Inject(method = "method_47413(Lnet/minecraft/class_332;IIF)V", at = @At("HEAD"),
            cancellable = true, remap = false, require = 0)
    private void prissma$hideRoutedCrafting(CallbackInfo callback) {
        if (GamePanelBridge.hidesCrafting(this)) callback.cancel();
    }
}
