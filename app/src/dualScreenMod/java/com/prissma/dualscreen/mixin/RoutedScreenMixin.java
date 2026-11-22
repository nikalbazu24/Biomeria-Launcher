package com.prissma.dualscreen.mixin;

import com.prissma.dualscreen.GamePanelBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.class_437", remap = false)
abstract class RoutedScreenMixin {
    @Inject(method = "method_47413(Lnet/minecraft/class_332;IIF)V", at = @At("HEAD"),
            cancellable = true, remap = false, require = 0)
    private void prissma$renderRoutedMenu(@Coerce Object draw, int x, int y, float delta, CallbackInfo callback) {
        if (GamePanelBridge.hidesRoutedScreen(this)) callback.cancel();
    }
}
