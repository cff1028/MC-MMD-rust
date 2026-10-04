package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.compat.vr.pointer.VrPointerRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TAIL callbacks run in ascending priority: after Vivecraft (999) and the opaque mirror (1000). */
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class PointerLevelRendererMixin {
    @Inject(method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V", at = @At("TAIL"))
    private void mmdskin$pointerWithSceneDepth(com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator,
            net.minecraft.client.DeltaTracker delta, boolean outline, net.minecraft.client.Camera camera,
            net.minecraft.client.renderer.GameRenderer renderer, org.joml.Matrix4f view,
            org.joml.Matrix4f projection, CallbackInfo ci) {
        VrPointerRenderer.renderWorld(projection);
    }
}
