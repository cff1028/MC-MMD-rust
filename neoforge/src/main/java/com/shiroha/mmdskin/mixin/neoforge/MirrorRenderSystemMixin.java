package com.shiroha.mmdskin.mixin.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorScenePass;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Isolate additional world rendering from the eye's matrix stack, even on failure. */
@Mixin(value = RenderSystem.class, remap = false)
public abstract class MirrorRenderSystemMixin {
    @Inject(method = "getModelViewStack", at = @At("HEAD"), cancellable = true)
    private static void mmdskin$mirrorStack(CallbackInfoReturnable<Matrix4fStack> callback) {
        if (VrMirrorScenePass.isRendering()) callback.setReturnValue(VrMirrorScenePass.modelViewStack());
    }
    @Inject(method = "getModelViewMatrix", at = @At("HEAD"), cancellable = true)
    private static void mmdskin$mirrorMatrix(CallbackInfoReturnable<Matrix4f> callback) {
        if (VrMirrorScenePass.isRendering()) callback.setReturnValue(VrMirrorScenePass.modelViewStack());
    }
}
