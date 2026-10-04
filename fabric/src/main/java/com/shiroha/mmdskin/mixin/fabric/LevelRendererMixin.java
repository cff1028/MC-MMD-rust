/** 客户端 LevelRenderer 渲染注入与可见实体筛选。 */
package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.compat.vr.VRArmHider;
import com.shiroha.mmdskin.fabric.YsmCompat;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.renderer.compat.IrisCompat;
import com.shiroha.mmdskin.renderer.integration.player.PlayerPerformanceGate;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;
import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
        at = @At("HEAD")
    )
    private void mmdskin$beginRenderFrame(com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator,
            net.minecraft.client.DeltaTracker deltaTracker, boolean outline, Camera camera,
            net.minecraft.client.renderer.GameRenderer renderer, org.joml.Matrix4f view,
            org.joml.Matrix4f projection, CallbackInfo ci) {
        PlayerPerformanceGate.beginRenderFrame();
        com.shiroha.mmdskin.compat.vr.mirror.VrMirrorRenderer.beginEye(camera, view,
                deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
        at = @At("TAIL")
    )
    private void mmdskin$renderVrMirror(com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator,
            net.minecraft.client.DeltaTracker deltaTracker, boolean outline, Camera camera,
            net.minecraft.client.renderer.GameRenderer renderer, org.joml.Matrix4f view,
            org.joml.Matrix4f projection, CallbackInfo ci) {
        com.shiroha.mmdskin.compat.vr.mirror.VrMirrorRenderer.finishEye();
        com.shiroha.mmdskin.compat.vr.VrCalibrationHudRenderer.render(camera, view);
    }

    @Redirect(
        method = "collectVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Ljava/util/List;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z", ordinal = 0)
    )
    private boolean onCameraIsDetached(Camera camera) {
        if (IrisCompat.isRenderingShadows()) {
            return camera.isDetached();
        }

        Entity entity = camera.getEntity();
        if (!(entity instanceof AbstractClientPlayer player)) {
            return camera.isDetached();
        }

        String playerName = player.getName().getString();
        String selectedModel = PlayerModelSyncManager.getPlayerModel(player.getUUID(), playerName, true);
        boolean isMmdDefault = selectedModel == null || selectedModel.isEmpty()
                || selectedModel.equals("榛樿 (鍘熺増娓叉煋)");
        boolean isMmdActive = !isMmdDefault;
        boolean isVanillaMmdModel = isMmdActive && (selectedModel.equals("VanillaModel")
                || selectedModel.equalsIgnoreCase("vanilla")
                || selectedModel.equals("VanilaModel")
                || selectedModel.equalsIgnoreCase("vanila"));

        if (isMmdActive && !isVanillaMmdModel && VRArmHider.isLocalPlayerInVR()) {
            return true;
        }

        if (FirstPersonManager.shouldRenderFirstPerson() && isMmdActive && !isVanillaMmdModel) {
            if (YsmCompat.isYsmModelActive(player)) {
                if (YsmCompat.isDisableSelfModel()) {
                    return camera.getXRot() >= 0;
                }
                return false;
            }
            return camera.getXRot() >= 0;
        }

        return camera.isDetached();
    }
}
