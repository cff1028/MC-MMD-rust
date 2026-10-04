package com.shiroha.mmdskin.compat.vr.mirror;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.profiling.Profiler;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** An additional world pass, completed before the main eye's world pass begins. */
public final class VrMirrorScenePass {
    private static boolean rendering;
    private static Matrix4fStack modelViewStack;

    private VrMirrorScenePass() {}

    public static boolean isRendering() { return rendering; }
    public static Matrix4fStack modelViewStack() { return modelViewStack; }

    @FunctionalInterface
    public interface AfterLevel {
        void render(Camera camera, Matrix4f view, Matrix4f projection);
    }

    public static void render(RenderTarget target, VrMirrorGeometry.ReflectedView reflected,
                              GraphicsResourceAllocator allocator, DeltaTracker delta,
                              GameRenderer gameRenderer, AfterLevel afterLevel) {
        if (rendering) return;
        Minecraft mc = Minecraft.getInstance();
        MirrorMinecraftAccess minecraftAccess = (MirrorMinecraftAccess) mc;
        MirrorGameRendererAccess rendererAccess = (MirrorGameRendererAccess) gameRenderer;
        Runnable restoreLevel = () -> {};
        Camera originalCamera = gameRenderer.getMainCamera();
        RenderTarget originalTarget = mc.getMainRenderTarget();
        boolean originalSmartCull = mc.smartCull;
        // A failed mod draw may leave unmatched pushes. Keep them out of the eye's stack.
        modelViewStack = new Matrix4fStack(64);
        rendering = true;
        Profiler.get().push("mmd_mirror_world");
        try (VivecraftPass ignored = new VivecraftPass(gameRenderer);
             SodiumMirrorCompat.Scope sodium = SodiumMirrorCompat.begin()) {
            restoreLevel = ((MirrorLevelRendererAccess) mc.levelRenderer).mmdskin$saveMirrorState(target);
            Camera camera = new ReflectedCamera(reflected, delta.getGameTimeDeltaPartialTick(false));
            minecraftAccess.mmdskin$replaceRenderTarget(target);
            rendererAccess.mmdskin$replaceCamera(camera);
            mc.smartCull = false;
            target.bindWrite(true);
            RenderSystem.getModelViewStack().identity();
            mc.levelRenderer.prepareCullFrustum(reflected.eye(), reflected.rotation(), reflected.projection());
            // Use the installed renderer, including terrain, entities, block entities,
            // particles, weather, transparency and other mods' world-render injections.
            mc.levelRenderer.renderLevel(allocator, delta, false, camera, gameRenderer,
                    reflected.rotation(), reflected.projection());
            afterLevel.render(camera, reflected.rotation(), reflected.projection());
        } finally {
            try {
                minecraftAccess.mmdskin$replaceRenderTarget(originalTarget);
                rendererAccess.mmdskin$replaceCamera(originalCamera);
                mc.smartCull = originalSmartCull;
                restoreLevel.run();
                mc.getEntityRenderDispatcher().prepare(mc.level, originalCamera, mc.crosshairPickEntity);
                mc.getBlockEntityRenderDispatcher().prepare(mc.level, originalCamera, mc.hitResult);
            } finally {
                rendering = false;
                modelViewStack = null;
                Profiler.get().pop();
            }
        }
    }

    private static final class ReflectedCamera extends Camera {
        ReflectedCamera(VrMirrorGeometry.ReflectedView reflected, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            setup(mc.level, mc.player, true, false, partialTick);
            setPosition(reflected.eye());
            Quaternionf rotation = reflected.rotation().getUnnormalizedRotation(new Quaternionf()).conjugate();
            var direction = new org.joml.Vector3f(0, 0, -1).rotate(rotation);
            setRotation((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                    (float) Math.toDegrees(-Math.asin(Math.clamp(direction.y, -1f, 1f))));
            rotation().set(rotation);
            getLookVector().set(0, 0, -1).rotate(rotation);
            getUpVector().set(0, 1, 0).rotate(rotation);
            getLeftVector().set(-1, 0, 0).rotate(rotation);
        }
    }

    /** Scope render routing without changing VR runtime/tracking or saved settings. */
    private static final class VivecraftPass implements AutoCloseable {
        private final Field type;
        private final Field pass;
        private final Field worldPass;
        private final Object holder;
        private final Object oldType;
        private final Object oldPass;
        private final Object oldWorldPass;
        private final Object gameRenderer;
        private final Method setupRve;
        private final Method cacheRve;

        VivecraftPass(GameRenderer renderer) {
            Method restoreRve;
            Object vanillaType;
            Object vanillaPass;
            try {
                Class<?> manager = Class.forName("org.vivecraft.client_xr.render_pass.RenderPassManager");
                Class<?> holderClass = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
                holder = holderClass.getMethod("getInstance").invoke(null);
                type = manager.getField("RENDER_PASS_TYPE");
                worldPass = manager.getField("WRP");
                pass = holderClass.getField("currentPass");
                oldType = type.get(null);
                oldWorldPass = worldPass.get(null);
                oldPass = pass.get(holder);
                gameRenderer = renderer;
                Class<?> extension = Class.forName("org.vivecraft.client_vr.extensions.GameRendererExtension");
                setupRve = extension.getMethod("vivecraft$setupRVE");
                cacheRve = extension.getMethod("vivecraft$cacheRVEPos", net.minecraft.world.entity.Entity.class);
                restoreRve = extension.getMethod("vivecraft$restoreRVEPos", net.minecraft.world.entity.Entity.class);
                vanillaType = type.getType().getField("VANILLA").get(null);
                vanillaPass = pass.getType().getField("VANILLA").get(null);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unable to scope Vivecraft mirror world rendering", failure);
            }
            // A constructor that throws is not closed by try-with-resources.
            // Resolve everything first, then roll back any partially applied switch.
            try {
                restoreRve.invoke(renderer, Minecraft.getInstance().getCameraEntity());
                type.set(null, vanillaType);
                pass.set(holder, vanillaPass);
                worldPass.set(null, null);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                try {
                    close();
                } catch (RuntimeException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
                throw new IllegalStateException("Unable to scope Vivecraft mirror world rendering", failure);
            }
        }

        @Override
        public void close() {
            try {
                type.set(null, oldType);
                pass.set(holder, oldPass);
                worldPass.set(null, oldWorldPass);
                cacheRve.invoke(gameRenderer, Minecraft.getInstance().getCameraEntity());
                setupRve.invoke(gameRenderer);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unable to restore Vivecraft eye rendering", failure);
            }
        }
    }
}
