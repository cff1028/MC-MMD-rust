package com.shiroha.mmdskin.compat.vr;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.FogParameters;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Scoped OpenGL state for a VR layer; restores the actual eye/GUI target. */
public final class VrRenderState implements AutoCloseable {
    private final int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final double[] depthRange = new double[2];
    private final int[] viewport = new int[4];
    private final int[] scissorBox = new int[4];
    private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
    private final ProjectionType projectionType = RenderSystem.getProjectionType();
    private final Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
    private final CompiledShaderProgram shader = RenderSystem.getShader();
    private final FogParameters fog = RenderSystem.getShaderFog();
    private final float[] color = RenderSystem.getShaderColor().clone();
    private final int[] textures = new int[12];
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
    private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
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

    public VrRenderState() {
        GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, depthRange);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        for (int i = 0; i < textures.length; i++) textures[i] = RenderSystem.getShaderTexture(i);
        RenderSystem.getModelViewStack().pushMatrix();
    }

    public void restoreTargetAndMatrices() {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        RenderSystem.setProjectionMatrix(projection, projectionType);
        RenderSystem.getModelViewStack().set(modelView);
        RenderSystem.setShaderFog(fog);
        if (stencil) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
        if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        else RenderSystem.disableScissor();
    }

    @Override
    public void close() {
        restoreTargetAndMatrices();
        RenderSystem.getModelViewStack().popMatrix();
        if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        RenderSystem.depthMask(depthMask);
        RenderSystem.depthFunc(depthFunc);
        GL11.glDepthRange(depthRange[0], depthRange[1]);
        GL11.glCullFace(cullMode);
        RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
        for (int i = 0; i < textures.length; i++) RenderSystem.setShaderTexture(i, textures[i]);
        RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
        RenderSystem.setShader(shader);
        com.mojang.blaze3d.platform.GlStateManager._glUseProgram(program);
        RenderSystem.activeTexture(activeTexture);
    }
}
