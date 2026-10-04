package com.shiroha.mmdskin.compat.vr.pointer;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorController;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorScenePass;
import com.shiroha.mmdskin.config.VrPointerConfigData;
import com.shiroha.mmdskin.config.VrPointerConfigManager;
import com.shiroha.mmdskin.renderer.compat.IrisCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import java.util.ArrayList;
import java.util.List;

/** Visual aid only: neither changes Minecraft's hit result nor consumes controller actions. */
public final class VrPointerRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    // Physical left, physical right. Vivecraft's action/main-hand indices may be reversed.
    private static final VrPointerAnimation[] animations = {new VrPointerAnimation(), new VrPointerAnimation()};
    private static final DrawHand[] draws = new DrawHand[2];
    private static final SurfaceRay[] previousRays = new SurfaceRay[2];
    private static long lastSeen;
    private static Object lastLevel;
    private static boolean failureLogged;

    private VrPointerRenderer() {}

    /** Called after Vivecraft has placed and drawn its GUI planes, in their actual render target. */
    public static void render() {
        render(false, null);
    }

    /** The composed world target has valid scene depth, unlike Fabulous's unoccluded GUI target. */
    public static void renderWorld(Matrix4f projection) {
        if (Minecraft.getInstance().level != null) render(true, projection);
    }

    private static void render(boolean worldPass, Matrix4f worldProjection) {
        if (VrMirrorScenePass.isRendering() || IrisCompat.isRenderingShadows()) return;
        try {
            VivecraftPointerBridge.Frame frame = VivecraftPointerBridge.readFrame();
            // Camera/scope passes can occur between eyes: they must not restart the fades.
            if (frame == null) return;
            Minecraft mc = Minecraft.getInstance();
            long now = System.nanoTime();
            if (lastLevel != mc.level || lastSeen != 0 && now - lastSeen > 2_000_000_000L) {
                reset(); lastLevel = mc.level;
            }
            lastSeen = now;
            VrPointerConfigData config = VrPointerConfigManager.getLiveConfig();
            // GUI placement and dragged mirrors can change between stages of the same frame.
            // Animation clocks are frozen per frame; refreshing geometry cannot double-step them.
            update(frame, config, now);
            if (draws[0] == null && draws[1] == null) return;
            try (RenderState ignored = new RenderState()) {
                if (worldProjection != null) RenderSystem.setProjectionMatrix(worldProjection, ProjectionType.PERSPECTIVE);
                RenderSystem.getModelViewStack().identity();
                RenderSystem.setShader(CoreShaders.POSITION_COLOR);
                RenderSystem.setShaderColor(1, 1, 1, 1);
                RenderSystem.setShaderFog(FogParameters.NO_FOG);
                RenderSystem.enableBlend();
                GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                RenderSystem.depthMask(false);
                RenderSystem.disableCull();
                for (DrawHand hand : draws) {
                    if (hand == null) continue;
                    if (mc.level != null && worldPass == hand.ray.overlay) continue;
                    // GUI layers deliberately render in front of the world; their pointer must too.
                    if (hand.ray.overlay) RenderSystem.disableDepthTest();
                    else { RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_LEQUAL); }
                    draw(hand, frame.view(), config);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            reset();
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("VR pointer rendering unavailable; preserving Vivecraft input and rendering", failure);
            }
        }
    }

    private static void update(VivecraftPointerBridge.Frame frame, VrPointerConfigData config, long now) {
        var settings = new VrPointerAnimation.Settings(config.rayColor, config.dotColor,
                config.pressedRayColor, config.pressedDotColor, config.dotRadiusView, config.pressedDotRadiusView,
                config.colorAnimationEnabled, config.colorDurationMs,
                config.radiusAnimationEnabled, config.radiusDurationMs, config.fadeInMs, config.fadeOutMs);
        List<VrPointerGeometry.Plane> uiPlanes = new ArrayList<>();
        for (var plane : frame.planes()) uiPlanes.add(new VrPointerGeometry.Plane(plane.id(),
                vector(plane.center()), vector(plane.right()), vector(plane.up()), plane.width() / 2, plane.height() / 2));
        List<VrPointerGeometry.Plane> mirrorPlanes = VrPointerMirrorSurfaces.current();
        boolean uiVisible = frame.uiVisible() || VrMirrorController.isEnabled();
        for (int hand = 0; hand < 2; hand++) {
            boolean physicalLeft = hand == 0;
            VivecraftPointerBridge.Controller controller = null;
            for (var candidate : frame.controllers()) if (candidate.physicalLeft() == physicalLeft) controller = candidate;
            boolean enabled = physicalLeft ? config.leftEnabled : config.rightEnabled;
            // Keep picking while fading, including when the mode or this hand has been disabled.
            boolean updateRay = enabled && config.visibility != VrPointerConfigData.Visibility.OFF
                    || previousRays[hand] != null;
            SurfaceRay picked = controller != null && updateRay
                    ? pick(controller, uiPlanes, mirrorPlanes, config.maxDistance * frame.view().worldScale()) : null;
            boolean visible = VrPointerVisibility.visible(config.visibility, controller != null, enabled,
                    uiVisible, picked != null && picked.uiHit);
            boolean pressed = controller != null && switch (config.pressBinding) {
                case TRIGGER -> controller.triggerDown();
                case GRIP -> controller.gripDown();
                case TRIGGER_OR_GRIP -> controller.triggerDown() || controller.gripDown();
            };
            var sample = animations[hand].sample(frame.frameId(), now, visible, pressed, settings);
            // Fade only the opacity: origin, direction and surface hit still follow the tracked hand.
            // Retain the last geometry only when tracking is unavailable.
            if (picked != null) previousRays[hand] = picked;
            SurfaceRay ray = previousRays[hand];
            draws[hand] = ray != null && sample.visibility() > 0.0001f ? new DrawHand(ray, sample) : null;
            if (!visible && draws[hand] == null) previousRays[hand] = null;
        }
    }

    private static SurfaceRay pick(VivecraftPointerBridge.Controller controller,
                                   List<VrPointerGeometry.Plane> planes,
                                   List<VrPointerGeometry.Plane> mirrorPlanes, double distance) {
        Vec3 origin = controller.origin(), direction = controller.direction().normalize();
        var guiHit = VrPointerGeometry.nearest(vector(origin), vector(direction), planes, distance);
        if (guiHit != null) {
            Vec3 normal = vec(new Vector3d(guiHit.plane().right()).cross(guiHit.plane().up()).normalize());
            return new SurfaceRay(origin, vec(guiHit.position()), normal, true, true, true);
        }
        Vec3 end = origin.add(direction.scale(distance));
        Vec3 normal = direction.scale(-1);
        boolean hit = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            BlockHitResult block = mc.level.clip(new ClipContext(origin, end,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (block.getType() != HitResult.Type.MISS) {
                end = block.getLocation();
                normal = Vec3.atLowerCornerOf(block.getDirection().getUnitVec3i());
                hit = true;
            }
            EntityHitResult entity = ProjectileUtil.getEntityHitResult(mc.player, origin, end,
                    new AABB(origin, end).inflate(1), e -> !e.isSpectator() && e.isPickable(), origin.distanceToSqr(end));
            if (entity != null) { end = entity.getLocation(); normal = direction.scale(-1); hit = true; }
        }
        // Use the visible surface, including its depth offset: the logical mirror plane is behind it.
        var mirrorHit = VrPointerGeometry.nearest(vector(origin), vector(direction), mirrorPlanes,
                Math.min(distance, origin.distanceTo(end)));
        if (mirrorHit != null) {
            normal = vec(new Vector3d(mirrorHit.plane().right()).cross(mirrorHit.plane().up()).normalize());
            return new SurfaceRay(origin, vec(mirrorHit.position()), normal, true, false, true);
        }
        return new SurfaceRay(origin, end, normal, hit, false, false);
    }

    private static void draw(DrawHand hand, VivecraftPointerBridge.View view, VrPointerConfigData config) {
        SurfaceRay ray = hand.ray;
        var sample = hand.sample;
        Vec3 from = ray.origin.subtract(view.eye());
        Vec3 end = ray.end.subtract(view.eye());
        Matrix4f matrix = view.view();
        float width = config.rayWidth * view.worldScale();
        float rayAlpha = config.rayAlpha * sample.visibility();
        if (rayAlpha > 0.001f && from.distanceToSqr(end) > 1e-12) {
            Vec3 along = end.subtract(from).normalize();
            Vec3 side = along.cross(from.add(end).scale(-0.5)).normalize();
            if (side.lengthSqr() < .5) side = along.cross(Math.abs(along.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            // A soft-edged ribbon has a predictable physical width on every graphics driver.
            strip(buffer, matrix, from, end, side, -.5 * width, -.32 * width, sample.rayColor(), 0, rayAlpha);
            strip(buffer, matrix, from, end, side, -.32 * width, .32 * width, sample.rayColor(), rayAlpha, rayAlpha);
            strip(buffer, matrix, from, end, side, .32 * width, .5 * width, sample.rayColor(), rayAlpha, 0);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }
        float dotAlpha = config.dotAlpha * sample.visibility();
        if (config.dotEnabled && ray.hit && dotAlpha > .001f) {
            Vec3 normal = ray.normal.normalize();
            if (normal.dot(end.scale(-1)) < 0) normal = normal.scale(-1);
            Vec3 center = end.add(normal.scale(Math.max(.0001, .0008 * view.worldScale())));
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            Matrix4d eyeTransform = new Matrix4d(matrix);
            var disc = VrPointerDotGeometry.create(eyeTransform.transformPosition(vector(center)),
                    eyeTransform.transformDirection(vector(normal)), RenderSystem.getProjectionMatrix(),
                    viewport[2], viewport[3], sample.dotRadius());
            if (disc == null) return;
            // Already in eye space. Projecting the circle onto the hit plane keeps depth and a
            // round screen footprint even when the UI/mirror is tilted or viewed from far away.
            Matrix4f eyeSpace = new Matrix4f();
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < disc.outer().length; i++) {
                int next = (i + 1) % disc.outer().length;
                Vec3 innerA = vec(disc.inner()[i]), innerB = vec(disc.inner()[next]);
                Vec3 outerA = vec(disc.outer()[i]), outerB = vec(disc.outer()[next]);
                vertex(buffer, eyeSpace, vec(disc.center()), sample.dotColor(), dotAlpha);
                vertex(buffer, eyeSpace, innerA, sample.dotColor(), dotAlpha);
                vertex(buffer, eyeSpace, innerB, sample.dotColor(), dotAlpha);
                vertex(buffer, eyeSpace, innerA, sample.dotColor(), dotAlpha);
                vertex(buffer, eyeSpace, outerA, sample.dotColor(), 0);
                vertex(buffer, eyeSpace, outerB, sample.dotColor(), 0);
                vertex(buffer, eyeSpace, innerA, sample.dotColor(), dotAlpha);
                vertex(buffer, eyeSpace, outerB, sample.dotColor(), 0);
                vertex(buffer, eyeSpace, innerB, sample.dotColor(), dotAlpha);
            }
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }
    }

    private static void strip(BufferBuilder buffer, Matrix4f matrix, Vec3 from, Vec3 to, Vec3 side,
                              double left, double right, int rgb, float leftAlpha, float rightAlpha) {
        vertex(buffer, matrix, from.add(side.scale(left)), rgb, leftAlpha);
        vertex(buffer, matrix, to.add(side.scale(left)), rgb, leftAlpha);
        vertex(buffer, matrix, to.add(side.scale(right)), rgb, rightAlpha);
        vertex(buffer, matrix, from.add(side.scale(right)), rgb, rightAlpha);
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix, Vec3 position, int rgb, float alpha) {
        buffer.addVertex(matrix, (float) position.x, (float) position.y, (float) position.z)
                .setColor((rgb & 0xFFFFFF) | Math.clamp(Math.round(alpha * 255), 0, 255) << 24);
    }
    private static Vector3d vector(Vec3 value) { return new Vector3d(value.x, value.y, value.z); }
    private static Vec3 vec(org.joml.Vector3dc value) { return new Vec3(value.x(), value.y(), value.z()); }

    public static void reset() {
        for (int i = 0; i < 2; i++) { animations[i].reset(); draws[i] = null; previousRays[i] = null; }
        lastSeen = 0;
    }

    private record SurfaceRay(Vec3 origin, Vec3 end, Vec3 normal, boolean hit, boolean overlay, boolean uiHit) {}
    private record DrawHand(SurfaceRay ray, VrPointerAnimation.Sample sample) {}

    /** No framebuffer, texture or viewport changes; restore all state this pass touches. */
    private static final class RenderState implements AutoCloseable {
        private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        private final ProjectionType projectionType = RenderSystem.getProjectionType();
        private final Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        private final CompiledShaderProgram shader = RenderSystem.getShader();
        private final FogParameters fog = RenderSystem.getShaderFog();
        private final float[] color = RenderSystem.getShaderColor().clone();
        private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                blend = GL11.glIsEnabled(GL11.GL_BLEND), depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC), program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private RenderState() { RenderSystem.getModelViewStack().pushMatrix(); }
        @Override public void close() {
            RenderSystem.setProjectionMatrix(projection, projectionType);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.getModelViewStack().set(modelView);
            RenderSystem.setShaderFog(fog);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            RenderSystem.depthMask(depthMask);
            RenderSystem.depthFunc(depthFunc);
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.setShader(shader);
            GlStateManager._glUseProgram(program);
        }
    }
}
