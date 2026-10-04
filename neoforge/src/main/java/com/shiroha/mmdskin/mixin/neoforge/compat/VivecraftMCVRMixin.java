package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.compat.vr.VivecraftMirrorInput;
import com.shiroha.mmdskin.compat.vr.VrCalibrationController;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Claim raw VR actions before hotbar and Minecraft attack consumers can use them. */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.provider.MCVR", remap = false)
public abstract class VivecraftMCVRMixin {
    @Inject(method = "processBindings()V", at = @At("HEAD"), remap = false, require = 0)
    private void mmdskin$captureWorldToolInput(CallbackInfo ci) {
        VrCalibrationController.tick();
        VrMirrorController.updateInteraction();
        VivecraftMirrorInput.refreshPriorities();
    }
}
