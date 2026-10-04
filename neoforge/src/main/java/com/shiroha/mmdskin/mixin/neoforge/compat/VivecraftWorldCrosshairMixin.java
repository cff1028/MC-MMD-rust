package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.compat.vr.pointer.VrOriginalCrosshairControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.VREffectsHelper", remap = false)
public abstract class VivecraftWorldCrosshairMixin {
    @Inject(method = "renderCrosshairAtDepth(Z)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void mmdskin$hideOriginalWorldCrosshair(boolean depthAlways, CallbackInfo ci) {
        if (VrOriginalCrosshairControl.shouldHide()) ci.cancel();
    }
}
