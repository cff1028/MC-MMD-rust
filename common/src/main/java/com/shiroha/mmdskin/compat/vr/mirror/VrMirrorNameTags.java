package com.shiroha.mmdskin.compat.vr.mirror;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.compat.vr.VRDataProvider;
import com.shiroha.mmdskin.config.ModelConfigManager;
import com.shiroha.mmdskin.player.model.PlayerModelResolver;
import com.shiroha.mmdskin.renderer.integration.ModelPropertyHelper;
import com.shiroha.mmdskin.renderer.runtime.model.MMDModelManager;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.network.chat.Component;

/** MMD replaces LivingEntityRenderer, so its name layer must also be submitted. */
public final class VrMirrorNameTags {
    private VrMirrorNameTags() {}

    public static void render(AbstractClientPlayer player, PlayerRenderState state, PoseStack pose,
                              MultiBufferSource buffers, int light, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (!VrMirrorScenePass.isRendering() || mc.options.hideGui || mc.player == null
                || player.isInvisibleTo(mc.player) || state.distanceToCameraSq > 4096) return;
        Component label = state.nameTag;
        if (player == mc.player) label = player.getDisplayName();
        if (label == null) return; // Preserve remote players' team/name visibility rules.
        double x = 0, y = player.getBbHeight() + .3, z = 0;
        String name = PlayerModelSyncManager.getPlayerModel(player.getUUID(), player.getName().getString(), player == mc.player);
        if (name != null) {
            var model = MMDModelManager.GetModel(name, PlayerModelResolver.getCacheKey(player));
            if (model != null) {
                float[] eye = new float[3];
                NativeFunc.GetInst().GetEyeBonePosition(model.model.getModelHandle(), eye);
                float scale = .09f * ModelPropertyHelper.getModelSize(model.properties)[0]
                        * ModelConfigManager.getLiveConfig(name).modelScale;
                double yaw = Math.toRadians(VRDataProvider.getBodyYawDegrees(player, partialTick));
                if (Float.isFinite(eye[0]) && Float.isFinite(eye[1]) && Float.isFinite(eye[2])) {
                    x = (Math.cos(yaw) * eye[0] - Math.sin(yaw) * eye[2]) * scale;
                    y = eye[1] * scale + .32;
                    z = (Math.sin(yaw) * eye[0] + Math.cos(yaw) * eye[2]) * scale;
                }
            }
        }
        pose.pushPose();
        try {
            pose.translate(x, y, z);
            pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            pose.scale(.025f, -.025f, .025f);
            float left = -mc.font.width(label) / 2f;
            int background = (int) (mc.options.getBackgroundOpacity(.25f) * 255) << 24;
            mc.font.drawInBatch(label, left, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, background, light);
        } finally {
            pose.popPose();
        }
    }
}
