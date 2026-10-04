package com.shiroha.mmdskin.compat.vr.mirror;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.compat.vr.VrRenderState;
import com.shiroha.mmdskin.compat.vr.VRDataProvider;
import com.shiroha.mmdskin.compat.vr.VrLinkedActions;
import com.shiroha.mmdskin.player.model.PlayerModelResolver;
import com.shiroha.mmdskin.renderer.api.RenderContext;
import com.shiroha.mmdskin.renderer.compat.IrisCompat;
import com.shiroha.mmdskin.renderer.integration.ModelPropertyHelper;
import com.shiroha.mmdskin.renderer.integration.player.ItemRenderHelper;
import com.shiroha.mmdskin.renderer.runtime.model.MMDModelManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/** Renders the live avatar and held items from a per-eye reflected viewpoint. */
public final class VrMirrorRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static TextureTarget target;
    private static ByteBufferBuilder itemBuffer;
    private static MultiBufferSource.BufferSource items;
    private static boolean rendering;
    private static boolean drawingAvatar;
    private static boolean scenePrepared;
    private static Camera eyeCamera;
    private static Matrix4f eyeView;
    private static float eyePartialTick;
    private static boolean eyePending;

    private VrMirrorRenderer() {}

    public static boolean isDrawingAvatar() { return drawingAvatar; }

    /** Queue a world-space mirror once per eye, before Vivecraft composites any GUI layers. */
    public static void beginEye(Camera camera, Matrix4f view, float partialTick) {
        if (VrMirrorScenePass.isRendering() || rendering) return;
        eyeCamera = camera;
        eyeView = new Matrix4f(view);
        eyePartialTick = partialTick;
        eyePending = VrMirrorController.isEnabled();
    }

    /** Called in the world target before Vivecraft switches to its GUI/hand targets. */
    public static void renderBeforeUi() {
        if (!eyePending || VrMirrorScenePass.isRendering() || rendering) return;
        eyePending = false;
        render(eyeCamera, eyeView, eyePartialTick);
    }

    /** Non-Vivecraft/fallback passes still get a mirror, never a second late overlay. */
    public static void finishEye() {
        if (VrMirrorScenePass.isRendering() || rendering) return;
        renderBeforeUi();
        eyeCamera = null;
        eyeView = null;
    }

    /** Run the additional world pass before the eye's own LevelRenderer starts. */
    public static void prepareScene(GraphicsResourceAllocator allocator, DeltaTracker delta,
                                    Camera camera, GameRenderer renderer, Matrix4f eyeView, Matrix4f eyeProjection,
                                    VrMirrorScenePass.AfterLevel afterLevel) {
        scenePrepared = false;
        if (VrMirrorScenePass.isRendering() || !VrMirrorController.isEnabled()
                || VrMirrorController.mode() != VrMirrorController.Mode.HIGH
                || IrisCompat.isRenderingShadows()) return;
        VrMirrorController.updateInteraction();
        VrMirrorGeometry mirror = VrMirrorController.geometry();
        if (mirror == null || !mirror.isVisibleFrom(camera.getPosition(), eyeView, eyeProjection)) return;
        var view = mirror.reflectedView(camera.getPosition(), renderer.getRenderDistance() * 4);
        if (view == null) return;
        try (VrRenderState saved = new VrRenderState()) {
            beginCapture(view);
            VrMirrorScenePass.render(target, view, allocator, delta, renderer, afterLevel);
            makeSceneOpaque();
            scenePrepared = true;
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("Full-scene VR mirror failed; restoring eye rendering and returning to low mode", failure);
            VrMirrorController.setMode(VrMirrorController.Mode.LOW);
            var player = Minecraft.getInstance().player;
            if (player != null) player.displayClientMessage(net.minecraft.network.chat.Component.translatableWithFallback(
                    "message.mmdskin.vr_mirror.scene_failed", "场景镜面与当前渲染器不兼容，已恢复 low 模式。详情见日志。"), true);
        }
    }

    /** Vanilla clears world alpha to zero; a physical mirror must still show that RGB sky. */
    private static void makeSceneOpaque() {
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        float[] clearColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            try {
                target.bindWrite(false);
                // Preserve color and depth, including fog-only pixels between the
                // sky disc and terrain. POSITION_TEX discards exactly zero alpha.
                RenderSystem.disableScissor();
                RenderSystem.colorMask(false, false, false, true);
                RenderSystem.clearColor(0, 0, 0, 1);
                RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT);
            } finally {
                RenderSystem.colorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
                RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
                if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
                else RenderSystem.disableScissor();
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            }
        }
    }

    public static void render(Camera camera, Matrix4f viewMatrix, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (!VrMirrorController.isEnabled() || rendering || VrMirrorScenePass.isRendering() || mc.player == null || mc.level == null
                || IrisCompat.isRenderingShadows()) return;
        VrMirrorController.updateInteraction();
        VrMirrorGeometry mirror = VrMirrorController.geometry();
        if (mirror == null || !mirror.isVisibleFrom(camera.getPosition(), viewMatrix,
                RenderSystem.getProjectionMatrix())) return;
        rendering = true;
        boolean failed = false;
        try (VrRenderState saved = new VrRenderState()) {
            var view = mirror.reflectedView(camera.getPosition());
            if (view != null && (VrMirrorController.mode() != VrMirrorController.Mode.HIGH || !scenePrepared)) {
                renderAvatar(view, partialTick);
            }
            saved.restoreTargetAndMatrices();
            RenderSystem.getModelViewStack().identity();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            int border = VrMirrorController.isGrabbed() ? 0xFFFFCA69
                    : VrMirrorController.isHighlighted() ? 0xFF72DDF7 : 0xFF344B61;
            drawFrame(mirror, camera.getPosition(), viewMatrix, border);
            if (view != null && target != null) drawSurface(mirror, camera.getPosition(), viewMatrix);
            VrMirrorControls.renderControls(mirror, camera.getPosition(), viewMatrix);
        } catch (RuntimeException e) {
            LOGGER.error("MMD VR mirror rendering failed; closing mirror", e);
            failed = true;
        } finally {
            rendering = false;
        }
        if (failed) VrMirrorController.reset();
    }

    private static void renderAvatar(VrMirrorGeometry.ReflectedView view, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        beginCapture(view);
        String name = VrLinkedActions.currentModelName();
        if (name == null) return;
        var model = MMDModelManager.GetModel(name, PlayerModelResolver.getCacheKey(mc.player));
        if (model == null) return;
        model.loadModelProperties(false);

        Vec3 origin = VRDataProvider.getRenderOrigin(mc.player, partialTick).subtract(view.eye());
        PoseStack stack = new PoseStack();
        stack.mulPose(view.rotation());
        stack.translate(origin.x, origin.y, origin.z);
        float size = ModelPropertyHelper.getModelSize(model.properties)[0];
        stack.scale(size, size, size);
        float yaw = VRDataProvider.getBodyYawDegrees(mc.player, partialTick);
        NativeFunc nativeApi = NativeFunc.GetInst();
        long handle = model.model.getModelHandle();
        boolean eyeMask = nativeApi.IsFirstPersonMode(handle);
        try {
            drawingAvatar = true;
            nativeApi.SetFirstPersonMode(handle, false);
            RenderSystem.setShader(CoreShaders.RENDERTYPE_ENTITY_TRANSLUCENT);
            model.model.render(mc.player, yaw, 0, new Vector3f(), partialTick,
                    stack, LightTexture.FULL_BRIGHT, RenderContext.MIRROR);
            ItemRenderHelper.renderItems(mc.player, model, stack, items, LightTexture.FULL_BRIGHT);
            items.endBatch();
        } finally {
            drawingAvatar = false;
            nativeApi.SetFirstPersonMode(handle, eyeMask);
        }
    }

    private static void beginCapture(VrMirrorGeometry.ReflectedView view) {
        if (target == null) {
            target = new MirrorTarget();
            target.setFilterMode(GL11.GL_LINEAR);
            target.setClearColor(0.045f, 0.06f, 0.08f, 1);
            itemBuffer = new ByteBufferBuilder(1 << 20);
            items = MultiBufferSource.immediate(itemBuffer);
        }
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.disableScissor();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        target.clear();
        target.bindWrite(true);
        RenderSystem.setProjectionMatrix(view.projection(), ProjectionType.PERSPECTIVE);
        RenderSystem.getModelViewStack().identity();
        RenderSystem.setShaderFog(FogParameters.NO_FOG);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    /** Vivecraft guards RenderTarget.clear's viewport against the active eye size. */
    private static final class MirrorTarget extends TextureTarget {
        private MirrorTarget() { super(768, 1280, true); }

        @Override
        public void clear() {
            bindWrite(false);
            RenderSystem.clearColor(0.045f, 0.06f, 0.08f, 1);
            RenderSystem.clearDepth(1);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            unbindWrite();
        }
    }

    private static void drawFrame(VrMirrorGeometry mirror, Vec3 camera, Matrix4f view, int color) {
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float x = VrMirrorGeometry.WIDTH / 2 + .025f;
        float y = VrMirrorGeometry.HEIGHT / 2 + .025f;
        colorVertex(builder, view, mirror.point(-x, -y).subtract(camera), color);
        colorVertex(builder, view, mirror.point(x, -y).subtract(camera), color);
        colorVertex(builder, view, mirror.point(x, y).subtract(camera), color);
        colorVertex(builder, view, mirror.point(-x, y).subtract(camera), color);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private static void drawSurface(VrMirrorGeometry mirror, Vec3 camera, Matrix4f view) {
        RenderSystem.setShader(CoreShaders.POSITION_TEX);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        float x = VrMirrorGeometry.WIDTH / 2;
        float y = VrMirrorGeometry.HEIGHT / 2;
        Vec3 offset = mirror.normal().scale(VrMirrorGeometry.SURFACE_OFFSET).subtract(camera);
        // The virtual camera looks out of the back of the plane: reverse U for a mirror, not a video screen.
        textureVertex(builder, view, mirror.point(-x, -y).add(offset), 1, 0);
        textureVertex(builder, view, mirror.point(x, -y).add(offset), 0, 0);
        textureVertex(builder, view, mirror.point(x, y).add(offset), 0, 1);
        textureVertex(builder, view, mirror.point(-x, y).add(offset), 1, 1);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private static void colorVertex(BufferBuilder builder, Matrix4f view, Vec3 pos, int color) {
        builder.addVertex(view, (float) pos.x, (float) pos.y, (float) pos.z).setColor(color);
    }

    private static void textureVertex(BufferBuilder builder, Matrix4f view, Vec3 pos, float u, float v) {
        builder.addVertex(view, (float) pos.x, (float) pos.y, (float) pos.z).setUv(u, v);
    }

    public static void release() {
        SodiumMirrorCompat.clearPending();
        scenePrepared = false;
        eyePending = false;
        eyeCamera = null;
        eyeView = null;
        var levelRenderer = Minecraft.getInstance().levelRenderer;
        if (levelRenderer instanceof MirrorLevelRendererAccess access) access.mmdskin$releaseMirrorTargets();
        if (target != null) {
            int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int deleted = target.frameBufferId;
            target.destroyBuffers();
            target = null;
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw == deleted ? 0 : draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read == deleted ? 0 : read);
        }
        if (itemBuffer != null) { itemBuffer.close(); itemBuffer = null; items = null; }
    }

}
