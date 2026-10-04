package com.shiroha.mmdskin.mixin.neoforge;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.shiroha.mmdskin.compat.vr.mirror.MirrorGameRendererAccess;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class MirrorGameRendererMixin implements MirrorGameRendererAccess {
    @Shadow @Final @Mutable private Camera mainCamera;
    @Override public Camera mmdskin$replaceCamera(Camera camera) {
        Camera previous = mainCamera;
        mainCamera = camera;
        return previous;
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void mmdskin$prepareMirrorWorld(LevelRenderer level, GraphicsResourceAllocator allocator,
            DeltaTracker delta, boolean outline, Camera camera, GameRenderer renderer,
            Matrix4f view, Matrix4f projection, Operation<Void> original) {
        VrMirrorRenderer.prepareScene(allocator, delta, camera, renderer, view, projection, (mirrorCamera, mirrorView, mirrorProjection) -> {
            net.neoforged.neoforge.client.ClientHooks.dispatchRenderStage(
                    net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL,
                    Minecraft.getInstance().level, level, null, mirrorView, mirrorProjection,
                    level.getTicks(), mirrorCamera, level.getFrustum());
        });
        original.call(level, allocator, delta, outline, camera, renderer, view, projection);
    }
}
