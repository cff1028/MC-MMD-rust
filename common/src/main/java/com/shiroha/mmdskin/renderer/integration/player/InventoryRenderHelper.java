package com.shiroha.mmdskin.renderer.integration.player;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.renderer.api.IMMDModel;
import com.shiroha.mmdskin.renderer.api.RenderContext;
import com.shiroha.mmdskin.renderer.runtime.model.AbstractMMDModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.FogParameters;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/**
 * 库存屏幕渲染辅助类。
 */
public class InventoryRenderHelper {

    public static void renderInInventory(AbstractClientPlayer player, IMMDModel model, float entityYaw,
                                        float tickDelta, PoseStack matrixStack, int packedLight, float[] size,
                                        IMMDModel worldPoseSource) {
        float inventorySize = size[1];
        matrixStack.scale(inventorySize, inventorySize, inventorySize);
        // InventoryScreen already supplies pixel scaling, projection, Z inversion,
        // translation and mouse tilt. The renderer supplies model scaling and body yaw.
        // Leave its resulting model matrix in place for held-item rendering.
        NativeFunc.GetInst().SetFirstPersonMode(model.getModelHandle(), false);
        try (PreviewRenderState ignored = new PreviewRenderState()) {
            RenderSystem.setShader(CoreShaders.RENDERTYPE_ENTITY_TRANSLUCENT);
            if (model instanceof AbstractMMDModel preview && worldPoseSource instanceof AbstractMMDModel source
                    && preview.renderInventoryPoseFrom(source, player, entityYaw, matrixStack, packedLight)) {
                return;
            }
            model.render(player, entityYaw, 0.0f, new Vector3f(), tickDelta,
                    matrixStack, packedLight, RenderContext.INVENTORY);
        }
    }

    /** Native model draws must not leave their material state active for GUI item slots. */
    private static final class PreviewRenderState implements AutoCloseable {
        private final CompiledShaderProgram shader = RenderSystem.getShader();
        private final FogParameters fog = RenderSystem.getShaderFog();
        private final float[] color = RenderSystem.getShaderColor().clone();
        private final int[] textures = new int[12];
        private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        private final int cullMode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

        private PreviewRenderState() {
            for (int i = 0; i < textures.length; i++) textures[i] = RenderSystem.getShaderTexture(i);
            RenderSystem.setShaderFog(FogParameters.NO_FOG);
            RenderSystem.setShaderColor(1, 1, 1, 1);
        }

        @Override public void close() {
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            RenderSystem.depthMask(depthMask);
            RenderSystem.depthFunc(depthFunc);
            GL11.glCullFace(cullMode);
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            for (int i = 0; i < textures.length; i++) RenderSystem.setShaderTexture(i, textures[i]);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.setShaderFog(fog);
            RenderSystem.setShader(shader);
            com.mojang.blaze3d.platform.GlStateManager._glUseProgram(program);
            RenderSystem.activeTexture(activeTexture);
        }
    }
}
