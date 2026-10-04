package com.shiroha.mmdskin.compat.vr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorScenePass;
import com.shiroha.mmdskin.compat.vr.pointer.VivecraftPointerBridge;
import com.shiroha.mmdskin.compat.vr.pointer.VrUiVisibility;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.player.model.PlayerModelResolver;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.renderer.api.RenderContext;
import com.shiroha.mmdskin.renderer.compat.IrisCompat;
import com.shiroha.mmdskin.renderer.integration.ModelPropertyHelper;
import com.shiroha.mmdskin.renderer.integration.player.ItemRenderHelper;
import com.shiroha.mmdskin.renderer.integration.player.PlayerRenderHelper;
import com.shiroha.mmdskin.renderer.runtime.model.MMDModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL11;

/** Draw the already-updated local avatar once, in the actual eye's UI target. */
public final class VrFirstPersonUiLayer {
    private static ByteBufferBuilder itemBuffer;
    private static MultiBufferSource.BufferSource items;
    private static boolean failed, drawing;
    private VrFirstPersonUiLayer() {}

    public static boolean shouldDefer() {
        if (failed || drawing || !ConfigManager.isVRModelAboveUi() || !ConfigManager.isVREnabled()) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && mc.player.isAlive() && !mc.player.isSpectator()
                && mc.getCameraEntity() == mc.player && mc.options.getCameraType().isFirstPerson()
                && (mc.screen != null || VrUiVisibility.isOverlayOpen())
                && !VrMirrorScenePass.isRendering() && !IrisCompat.isRenderingShadows()
                && FirstPersonManager.vrRuntime().isLocalPlayerInVr()
                && FirstPersonManager.vrRuntime().isLocalPlayerEyePass();
    }

    public static void render(float partialTick) {
        if (!shouldDefer()) { release(); return; }
        Minecraft mc = Minecraft.getInstance();
        var frame = VivecraftPointerBridge.readFrame();
        String name = VrLinkedActions.currentModelName();
        var model = name == null ? null : MMDModelManager.getLoadedModel(name, PlayerModelResolver.getCacheKey(mc.player));
        if (frame == null || model == null) return;
        var nativeApi = NativeFunc.GetInst();
        long handle = model.model.getModelHandle();
        boolean mask = nativeApi.IsFirstPersonMode(handle);
        drawing = true;
        try (VrRenderState saved = new VrRenderState()) {
            if (itemBuffer == null) { itemBuffer = new ByteBufferBuilder(1 << 20); items = MultiBufferSource.immediate(itemBuffer); }
            // Keep normal stereo projection and self-occlusion, reserving the near depth band
            // for this foreground layer. Never clear the eye/world/UI depth attachment.
            beginForegroundDepth();
            RenderSystem.getModelViewStack().identity();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShader(CoreShaders.RENDERTYPE_ENTITY_TRANSLUCENT);
            nativeApi.SetFirstPersonMode(handle, true);
            var origin = VRDataProvider.getRenderOrigin(mc.player, partialTick).subtract(frame.view().eye());
            PoseStack stack = new PoseStack();
            stack.mulPose(frame.view().view());
            stack.translate(origin.x, origin.y, origin.z);
            float size = ModelPropertyHelper.getModelSize(model.properties)[0];
            stack.scale(size, size, size);
            var params = PlayerRenderHelper.calculateRenderParams(mc.player, model, partialTick);
            int light = LevelRenderer.getLightColor(mc.level, mc.player.blockPosition());
            // MIRROR denotes a cached-pose draw; the first-person mask remains enabled here.
            model.model.render(mc.player, params.bodyYaw, params.bodyPitch, params.translation,
                    partialTick, stack, light, RenderContext.MIRROR);
            ItemRenderHelper.renderItems(mc.player, model, stack, items, light);
            items.endBatch();
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            LogManager.getLogger().warn("[VR UI] Foreground avatar layer failed; restoring normal world rendering", e);
            release();
        } finally {
            nativeApi.SetFirstPersonMode(handle, mask);
            drawing = false;
        }
    }

    static void beginForegroundDepth() {
        RenderSystem.disableScissor();
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        GL11.glDepthRange(0, .02);
    }

    public static void release() {
        if (itemBuffer != null) { itemBuffer.close(); itemBuffer = null; items = null; }
    }
}
