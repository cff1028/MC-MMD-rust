package com.shiroha.mmdskin.renderer.integration.player;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorScenePass;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.config.ModelConfigManager;
import com.shiroha.mmdskin.player.animation.AnimationStateManager;
import com.shiroha.mmdskin.player.animation.PendingAnimSignalCache;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.player.runtime.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.renderer.api.IMMDModel;
import com.shiroha.mmdskin.renderer.api.RenderContext;
import com.shiroha.mmdskin.renderer.api.RenderParams;
import com.shiroha.mmdskin.renderer.integration.ModelPropertyHelper;
import com.shiroha.mmdskin.renderer.runtime.bridge.ModelRuntimeBridge;
import com.shiroha.mmdskin.renderer.runtime.bridge.ModelRuntimeBridgeHolder;
import com.shiroha.mmdskin.renderer.runtime.model.AbstractMMDModel;
import com.shiroha.mmdskin.renderer.runtime.model.MMDModelManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * 文件职责：协调本地第一人称与 VR 状态下的玩家模型渲染时序。
 */
final class PlayerModelRenderCoordinator {

    private PlayerModelRenderCoordinator() {
    }

    static PlayerMixinDelegate.RenderAction render(PlayerRenderSelection selection,
                                                   AbstractClientPlayer player,
                                                   float entityYaw,
                                                   float tickDelta,
                                                   PoseStack matrixStack,
                                                   MultiBufferSource vertexConsumers,
                                                   int packedLight,
                                                   MMDModelManager.Model modelData) {
        IMMDModel model = modelData.model;
        modelData.loadModelProperties(false);

        float[] size = ModelPropertyHelper.getModelSize(modelData.properties);
        if (InventoryEntityRenderScope.isRendering(player)) {
            // The delegate selected a separate native instance. Never feed GUI entity
            // rotations into world VR tracking, eye caches or first-person masks.
            MMDModelManager.Model worldPose = selection.isLocalPlayer()
                    && FirstPersonManager.vrRuntime().isLocalPlayerInVr()
                    ? MMDModelManager.getLoadedModel(selection.selectedModel(), selection.playerCacheKey())
                    : null;
            matrixStack.pushPose();
            try {
                InventoryRenderHelper.renderInInventory(player, model, player.yBodyRot,
                        tickDelta, matrixStack, packedLight, size, worldPose == null ? null : worldPose.model);
                ItemRenderHelper.renderItems(player, modelData, matrixStack, vertexConsumers, packedLight);
                return PlayerMixinDelegate.RenderAction.CANCEL;
            } finally {
                matrixStack.popPose();
            }
        }
        if (VrMirrorScenePass.isRendering()) {
            return renderMirrorPose(player, tickDelta, matrixStack, vertexConsumers, packedLight, modelData, size);
        }
        float combinedScale = size[0] * ModelConfigManager.getLiveConfig(selection.selectedModel()).modelScale;
        boolean isVr = selection.isLocalPlayer() && FirstPersonManager.vrRuntime().isLocalPlayerInVr();
        syncVrState(modelData, player, tickDelta, isVr, 0.09f * combinedScale);

        ModelRuntimeBridge runtimeBridge = ModelRuntimeBridgeHolder.get();
        runtimeBridge.preRenderFirstPerson(model.getModelHandle(), combinedScale, selection.isLocalPlayer());
        boolean isFirstPerson = !isVr && selection.isLocalPlayer() && FirstPersonManager.isActive();

        if (!isVr) {
            AnimationStateManager.updateAnimationState(player, modelData);
        }
        consumePendingSignals(player, modelData, selection.isLocalPlayer());

        RenderParams params = PlayerRenderHelper.calculateRenderParams(player, modelData, tickDelta);
        boolean needsLocalRenderSync = selection.isLocalPlayer();

        matrixStack.pushPose();
        try {
            matrixStack.scale(size[0], size[0], size[0]);
            RenderSystem.setShader(CoreShaders.RENDERTYPE_ENTITY_TRANSLUCENT);
            boolean aboveUi = selection.isLocalPlayer() && com.shiroha.mmdskin.compat.vr.VrFirstPersonUiLayer.shouldDefer();
            RenderContext context = aboveUi ? RenderContext.VR_POSE_ONLY : isFirstPerson ? RenderContext.FIRST_PERSON : RenderContext.WORLD;
            model.render(player, params.bodyYaw, params.bodyPitch, params.translation, tickDelta, matrixStack, packedLight, context);

            if (needsLocalRenderSync) {
                runtimeBridge.postRenderFirstPerson(model.getModelHandle(), player, tickDelta);
                needsLocalRenderSync = false;
            }

            if (!aboveUi) ItemRenderHelper.renderItems(player, modelData, matrixStack, vertexConsumers, packedLight);
            return PlayerMixinDelegate.RenderAction.CANCEL;
        } finally {
            try {
                if (needsLocalRenderSync) {
                    runtimeBridge.postRenderFirstPerson(model.getModelHandle(), player, tickDelta);
                }
            } finally {
                matrixStack.popPose();
            }
        }
    }

    private static PlayerMixinDelegate.RenderAction renderMirrorPose(AbstractClientPlayer player,
                                                                      float tickDelta,
                                                                      PoseStack matrixStack,
                                                                      MultiBufferSource vertexConsumers,
                                                                      int packedLight,
                                                                      MMDModelManager.Model modelData,
                                                                      float[] size) {
        IMMDModel model = modelData.model;
        RenderParams params = PlayerRenderHelper.calculateRenderParams(player, modelData, tickDelta);
        NativeFunc nativeApi = NativeFunc.GetInst();
        long handle = model.getModelHandle();
        boolean firstPersonMask = nativeApi.IsFirstPersonMode(handle);
        matrixStack.pushPose();
        try {
            nativeApi.SetFirstPersonMode(handle, false);
            matrixStack.scale(size[0], size[0], size[0]);
            RenderSystem.setShader(CoreShaders.RENDERTYPE_ENTITY_TRANSLUCENT);
            model.render(player, params.bodyYaw, params.bodyPitch, params.translation,
                    tickDelta, matrixStack, packedLight, RenderContext.MIRROR);
            ItemRenderHelper.renderItems(player, modelData, matrixStack, vertexConsumers, packedLight);
            return PlayerMixinDelegate.RenderAction.CANCEL;
        } finally {
            try {
                nativeApi.SetFirstPersonMode(handle, firstPersonMask);
            } finally {
                matrixStack.popPose();
            }
        }
    }

    private static void syncVrState(MMDModelManager.Model modelData,
                                    AbstractClientPlayer player,
                                    float tickDelta,
                                    boolean isVr,
                                    float worldUnitsPerModelUnit) {
        IMMDModel model = modelData.model;
        if (!(model instanceof AbstractMMDModel abstractModel)) {
            return;
        }

        if (isVr) {
            LocalPlayer localPlayer = net.minecraft.client.Minecraft.getInstance().player;
            if (localPlayer != null && localPlayer.getUUID().equals(player.getUUID())) {
                FirstPersonManager.vrRuntime().applyMmdRenderState(true);
            }
            if (!abstractModel.isVrActive()) {
                MmdSkinRendererPlayerHelper.suppressDefaultAnimationState(modelData);
                FirstPersonManager.vrRuntime().setModelVrEnabled(model.getModelHandle(), true);
                abstractModel.setVrActive(true);
            }
            FirstPersonManager.vrRuntime().updateModelVr(
                    model.getModelHandle(),
                    player,
                    tickDelta,
                    ConfigManager.getVRArmIKStrength() * ModelConfigManager.getLiveConfig(modelData.getModelName()).vrArmIkStrength,
                    worldUnitsPerModelUnit
            );
            return;
        }

        LocalPlayer localPlayer = net.minecraft.client.Minecraft.getInstance().player;
        if (localPlayer != null && localPlayer.getUUID().equals(player.getUUID())) {
            FirstPersonManager.vrRuntime().applyMmdRenderState(false);
        }
        if (abstractModel.isVrActive()) {
            FirstPersonManager.vrRuntime().setModelVrEnabled(model.getModelHandle(), false);
            abstractModel.setVrActive(false);
            MmdSkinRendererPlayerHelper.resetModelAnimationState(player, modelData);
        }
    }

    private static void consumePendingSignals(AbstractClientPlayer player,
                                              MMDModelManager.Model modelData,
                                              boolean isLocalPlayer) {
        if (isLocalPlayer) {
            return;
        }

        PendingAnimSignalCache.SignalType signal = PendingAnimSignalCache.consume(player.getUUID());
        if (signal == PendingAnimSignalCache.SignalType.RESET) {
            MmdSkinRendererPlayerHelper.ResetPhysics(player);
        }
    }
}
