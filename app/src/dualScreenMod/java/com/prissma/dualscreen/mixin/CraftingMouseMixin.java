package com.prissma.dualscreen.mixin;

import com.prissma.dualscreen.GamePanelBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.class_312", remap = false)
abstract class CraftingMouseMixin {
    @Inject(method = {"method_1601(JIII)V", "method_1598(JDD)V"}, at = @At("HEAD"),
            cancellable = true, remap = false, require = 0)
    private void prissma$blockHiddenCraftingButtons(CallbackInfo callback) {
        if (GamePanelBridge.blocksCraftingMouse()) callback.cancel();
    }
}
