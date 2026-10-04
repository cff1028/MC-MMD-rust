package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.ui.spatial.SpatialMenuHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Input is evaluated after the current headset/controller room poses have been published. */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.gameplay.VRPlayer", remap = false)
public abstract class VivecraftSpatialMenuMixin {
    @Inject(method = "postPoll()V", at = @At("TAIL"), remap = false)
    private void mmdskin$pollSpatialMenu(CallbackInfo ci) { SpatialMenuHost.poll(); }
}
