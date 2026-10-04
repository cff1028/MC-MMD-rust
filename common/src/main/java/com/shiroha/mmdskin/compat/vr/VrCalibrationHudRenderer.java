package com.shiroha.mmdskin.compat.vr;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorScenePass;
import com.shiroha.mmdskin.renderer.compat.IrisCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** A binocular, head-centred world panel; never opens a Screen or uses the wrist-mounted action bar. */
public final class VrCalibrationHudRenderer {
    private static final float PIXEL_SCALE = 0.0035f;
    private static ByteBufferBuilder textBuffer;
    private static MultiBufferSource.BufferSource text;

    private VrCalibrationHudRenderer() {}

    public static void render(Camera camera, Matrix4f view) {
        var state = VrCalibrationController.panelState();
        Minecraft mc = Minecraft.getInstance();
        if (state == null || mc.player == null || mc.level == null || mc.screen != null
                || VrMirrorScenePass.isRendering() || IrisCompat.isRenderingShadows()
                || !VivecraftReflectionBridge.isLocalPlayerEyePass()) return;

        float[] tracking = VRDataProvider.getRenderTrackingData(mc.player);
        Quaternionf headRotation = new Quaternionf(camera.rotation());
        Vec3 head = VivecraftReflectionBridge.getWorldRenderHeadPosition(mc.player);
        if (head == null) head = camera.getPosition();
        if (validHead(tracking)) {
            head = new Vec3(tracking[0], tracking[1], tracking[2]);
            headRotation.set(tracking[3], tracking[4], tracking[5], tracking[6]).normalize();
        }
        Vector3f ahead = new Vector3f(0, -0.025f, -1.4f).rotate(headRotation);
        Vec3 relative = head.add(ahead.x, ahead.y, ahead.z).subtract(camera.getPosition());
        Matrix4f panel = new Matrix4f(view).translate((float) relative.x, (float) relative.y, (float) relative.z)
                .rotate(headRotation).scale(PIXEL_SCALE, -PIXEL_SCALE, PIXEL_SCALE);

        try (RenderState ignored = new RenderState()) {
            RenderSystem.getModelViewStack().identity();
            RenderSystem.setShaderFog(FogParameters.NO_FOG);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableScissor();
            drawPanel(panel, state);
        }
    }

    private static boolean validHead(float[] tracking) {
        if (tracking == null || tracking.length < 7) return false;
        float norm = 0;
        for (int i = 0; i < 7; i++) {
            if (!Float.isFinite(tracking[i])) return false;
            if (i >= 3) norm += tracking[i] * tracking[i];
        }
        return Float.isFinite(norm) && norm > 0.25f;
    }

    private static void drawPanel(Matrix4f matrix, VrCalibrationController.PanelState state) {
        int accent = state.completed() || state.ready() ? 0xFF65E2B4 : 0xFF7BC9FF;
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
        BufferBuilder shapes = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        rect(shapes, matrix, -214, -112, 214, 112, 0xF0132130);
        rect(shapes, matrix, -214, -112, 214, -109, accent);
        rect(shapes, matrix, -192, 44, 192, 54, 0xFF304353);
        rect(shapes, matrix, -192, 44, -192 + 384 * Math.clamp(state.progress(), 0, 1), 54, accent);
        // A deliberately simple T-pose silhouette remains readable while both hands are extended.
        rect(shapes, matrix, -154, -39, -138, -23, accent);
        rect(shapes, matrix, -192, -16, -100, -9, accent);
        rect(shapes, matrix, -150, -16, -142, 16, accent);
        rect(shapes, matrix, -153, 15, -146, 33, accent);
        rect(shapes, matrix, -145, 15, -138, 33, accent);
        BufferUploader.drawWithShader(shapes.buildOrThrow());

        if (textBuffer == null) {
            textBuffer = new ByteBufferBuilder(16384);
            text = MultiBufferSource.immediate(textBuffer);
        }
        Font font = Minecraft.getInstance().font;
        centered(font, label("title", "T 字站姿校准"), 0, -98, 0xFFFFFFFF, matrix);
        centered(font, label("instruction", "站直、看前方，双臂向两侧平举至肩高"), 0, -78, 0xFFE4EDF4, matrix);
        centered(font, label("confirm", "保持姿势，用前侧扳机（破坏键）确认"), 0, -64, 0xFFE4EDF4, matrix);
        int y = -32;
        for (var line : font.split(state.status(), 276)) {
            font.drawInBatch(line, -81, y, state.ready() || state.completed() ? 0xFF91F2CE : 0xFFFFDC92,
                    false, matrix, text, Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
            y += 13;
        }
        centered(font, label("progress", "姿势稳定进度：%s%%", Math.round(state.progress() * 100)), 0, 29, 0xFFDAE8F2, matrix);
        Component trigger = state.triggerDown()
                ? label("trigger_down", "扳机：已检测到按下")
                : label("trigger_up", "扳机：已松开");
        centered(font, trigger, 0, 66, state.triggerDown() ? 0xFF8CE9C6 : 0xFFDAE8F2, matrix);
        centered(font, label("press_count", "已检测按下：%s 次", state.detectedPresses()), 0, 81, 0xFFB9CCDC, matrix);
        centered(font, label("cancel", "菜单键取消 · 剩余 %s 秒", state.secondsRemaining()), 0, 97, 0xFFB9CCDC, matrix);
        text.endBatch();
    }

    private static void centered(Font font, Component label, float x, float y, int color, Matrix4f matrix) {
        font.drawInBatch(label, x - font.width(label) / 2.0f, y, color, false,
                matrix, text, Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
    }

    private static Component label(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("gui.mmdskin.vr_calibration." + key, fallback, args);
    }

    private static void rect(BufferBuilder builder, Matrix4f matrix, float left, float top, float right, float bottom, int color) {
        builder.addVertex(matrix, left, top, 0).setColor(color);
        builder.addVertex(matrix, left, bottom, 0).setColor(color);
        builder.addVertex(matrix, right, bottom, 0).setColor(color);
        builder.addVertex(matrix, right, top, 0).setColor(color);
    }

    public static void release() {
        if (textBuffer != null) {
            textBuffer.close();
            textBuffer = null;
            text = null;
        }
    }

    /** Font render types may change GL state; preserve the eye target and all state touched by this overlay. */
    private static final class RenderState implements AutoCloseable {
        private final int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
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

        private RenderState() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            for (int i = 0; i < textures.length; i++) textures[i] = RenderSystem.getShaderTexture(i);
            RenderSystem.getModelViewStack().pushMatrix();
        }

        @Override
        public void close() {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.setProjectionMatrix(projection, projectionType);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.getModelViewStack().set(modelView);
            RenderSystem.setShaderFog(fog);
            if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            else RenderSystem.disableScissor();
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
            RenderSystem.setShader(shader);
            GlStateManager._glUseProgram(program);
            RenderSystem.activeTexture(activeTexture);
        }
    }
}
