package com.biomeria.dualscreen.mixin;

import com.biomeria.dualscreen.Achievements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends advancement toasts to Biomeria's top-screen animation (ToastManager.add). */
@Mixin(targets = "net.minecraft.class_374", remap = false)
abstract class AchievementToastMixin {
    @Inject(method = "method_1999(Lnet/minecraft/class_368;)V", at = @At("HEAD"), cancellable = true,
            remap = false, require = 0)
    private void biomeria$celebrate(@Coerce Object toast, CallbackInfo callback) {
        if (Achievements.onToast(toast)) callback.cancel();
    }
}
