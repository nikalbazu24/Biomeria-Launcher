package com.prissma.dualscreen.mixin;

import com.prissma.dualscreen.GamePanelBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "xaero.map.MapProcessor", remap = false)
abstract class XaeroWorldRenderMixin {
    @Inject(method = "actuallySkippingWorldRender()Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void prissma$renderWorldWithLowerMap(CallbackInfoReturnable<Boolean> callback) {
        if (GamePanelBridge.keepsWorldVisibleUnderXaero()) callback.setReturnValue(false);
    }
}
