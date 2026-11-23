package com.prissma.dualscreen.mixin;

import com.prissma.dualscreen.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets Biomeria's character editor dress the local player (AbstractClientPlayerEntity.getSkinTextures). */
@Mixin(targets = "net.minecraft.class_742", remap = false)
abstract class PlayerSkinMixin {
    @Inject(method = "method_52814()Lnet/minecraft/class_8685;", at = @At("RETURN"),
            cancellable = true, remap = false, require = 0)
    private void prissma$chosenSkin(CallbackInfoReturnable<Object> callback) {
        Object original = callback.getReturnValue();
        Object chosen = PlayerSkin.textures(this, original);
        if (chosen != original) callback.setReturnValue(chosen);
    }
}
