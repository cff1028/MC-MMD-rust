package com.shiroha.mmdskin.ui.spatial;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.compat.vr.SteamVrMenuInput;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorRenderer;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorScenePass;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Optional Vivecraft bridge. Input is read from the poll pose; rendering uses each eye's render pose. */
public final class SpatialMenuVrBridge {
    public record Hand(boolean tracked, Matrix4f aim, boolean triggerActive, boolean triggerDown,
                       long triggerChangedAt, boolean gripActive, boolean gripDown, long gripChangedAt) {}
    public record Frame(long frameId, Vector3f headPosition, Vector3f headDirection, SpatialMenuTransforms.WorldFrame world,
                        Hand left, Hand right, float rightStickX, float rightStickY) {}
    private static Api api;
    private static boolean initialized, logged;
    private SpatialMenuVrBridge() {}
    public static void reset() { api = null; initialized = logged = false; }

    /** Picking-only query used before the eye pass by original-crosshair visibility policies. */
    public static boolean isPointingAtPanel() {
        Matrix4f panel = SpatialMenuHost.panelPose();
        Frame frame = panel == null ? null : pollFrame();
        if (frame == null) return false;
        for (Hand hand : new Hand[]{frame.left(), frame.right()}) if (hand.tracked()) {
            var hit = SpatialMenuGeometry.intersect(panel, SpatialMenuHost.panelWidth(), SpatialMenuHost.panelHeight(), hand.aim());
            if (hit != null && hit.inside()) return true;
        }
        return false;
    }

    public static Frame pollFrame() {
        Api a = api(); if (a == null) return null;
        try {
            Object holder = a.holder.invoke(null), settings = a.settings.get(holder), provider = a.provider.get(holder), player = a.player.get(holder);
            if (!a.running.getBoolean(null) || a.seated.getBoolean(settings) || player == null || provider == null
                    || !(boolean)a.headTracking.invoke(provider)) return null;
            Object room = a.room.get(player); if (room == null) return null;
            Object head = a.head.get(room);
            Vector3f position = new Vector3f((Vector3fc)a.position.invoke(head)), direction = new Vector3f((Vector3fc)a.direction.invoke(head));
            if (!position.isFinite() || !direction.isFinite() || direction.lengthSquared() < 1e-8f) return null;
            boolean reverse = a.reverse.getBoolean(settings);
            SteamVrMenuInput.Stick stick = SteamVrMenuInput.rightStick();
            // postPoll runs every headset frame, while world_pre only refreshes at game tick rate.
            Vec3 origin = (Vec3)a.roomOrigin.get(player);
            var world = new SpatialMenuTransforms.WorldFrame(origin.x, origin.y, origin.z,
                    (float)Math.toRadians(a.settingsRotation.getFloat(settings)), a.playerScale.getFloat(player));
            if (!world.valid()) return null;
            return new Frame(a.frameIndex.getLong(holder), position, direction.normalize(), world,
                    a.hand(room, provider, 0, reverse), a.hand(room, provider, 1, reverse), stick.x(), stick.y());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { failure(e); return null; }
    }

    /** The interpolated eye frame, used to keep fixed world anchors fixed between game ticks. */
    public static SpatialMenuTransforms.WorldFrame renderWorldFrame() {
        Api a = api(); if (a == null) return null;
        try {
            Object player = a.player.get(a.holder.invoke(null));
            Object world = player == null ? null : a.worldRender.get(player);
            if (world == null) return null;
            Vec3 origin = (Vec3)a.origin.get(world);
            var frame = new SpatialMenuTransforms.WorldFrame(origin.x, origin.y, origin.z,
                    a.worldRotation.getFloat(world), a.worldScale.getFloat(world));
            return frame.valid() ? frame : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { failure(e); return null; }
    }

    /** Maps a centered unit XY quad to per-eye clip space; dimensions are room metres. */
    public static Matrix4f renderMatrix(Matrix4fc roomPanel, float physicalWidth, float physicalHeight) {
        Api a = api(); if (a == null || VrMirrorScenePass.isRendering() || VrMirrorRenderer.isDrawingAvatar()) return null;
        try {
            if (!a.running.getBoolean(null) || (boolean)a.shadow.invoke(null)) return null;
            Object rendering = a.rendering.invoke(null);
            if (rendering == null || (boolean)a.vanilla.invoke(rendering)) return null;
            Object holder = a.holder.invoke(null), pass = a.pass.get(holder), player = a.player.get(holder);
            if (!(pass instanceof Enum<?> e) || !(e.name().equals("LEFT") || e.name().equals("RIGHT") || e.name().equals("CENTER")) || player == null) return null;
            Object world = a.worldRender.get(player); if (world == null) return null;
            Vec3 origin = (Vec3)a.origin.get(world), eye = (Vec3)a.worldPosition.invoke(a.eye.invoke(world, pass));
            // Subtract as doubles before conversion: large Minecraft coordinates must retain panel precision.
            Vector3f relative = new Vector3f((float)(origin.x - eye.x), (float)(origin.y - eye.y), (float)(origin.z - eye.z));
            Matrix4f modelView = SpatialMenuTransforms.modelView(roomPanel, physicalWidth, physicalHeight, relative,
                    a.worldRotation.getFloat(world), a.worldScale.getFloat(world), (Matrix4fc)a.eyeView.invoke(null, pass));
            return modelView == null ? null : new Matrix4f(RenderSystem.getProjectionMatrix()).mul(modelView);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { failure(e); return null; }
    }

    private static Api api() {
        if (!initialized) {
            initialized = true;
            try { api = new Api(); }
            catch (ClassNotFoundException absent) { /* Vivecraft is optional. */ }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) { failure(e); }
        }
        return api;
    }
    private static void failure(Throwable e) {
        if (!logged) { logged = true; LogManager.getLogger().warn("[VR menu] Vivecraft spatial pose bridge unavailable", e); }
    }

    private static final class Api {
        final Method holder, controller, position, direction, matrix, tracking, headTracking, eye, worldPosition, eyeView, rendering, vanilla, shadow;
        final Field settings, provider, player, room, worldRender, head, reverse, seated, running, frameIndex, pass, origin, worldRotation, worldScale, roomOrigin, playerScale, settingsRotation;
        Api() throws ReflectiveOperationException {
            Class<?> dh = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR"), data = Class.forName("org.vivecraft.client_vr.VRData"),
                    pose = Class.forName("org.vivecraft.client_vr.VRData$VRDevicePose"), passType = Class.forName("org.vivecraft.api.client.data.RenderPass"),
                    renderingType = Class.forName("org.vivecraft.api.client.VRRenderingAPI");
            holder = dh.getMethod("getInstance"); settings = dh.getField("vrSettings"); provider = dh.getField("vr"); player = dh.getField("vrPlayer");
            room = player.getType().getField("vrdata_room_pre"); worldRender = player.getType().getField("vrdata_world_render");
            roomOrigin = player.getType().getField("roomOrigin"); playerScale = player.getType().getField("worldScale");
            settingsRotation = settings.getType().getField("worldRotation");
            frameIndex = dh.getField("frameIndex"); pass = dh.getField("currentPass");
            reverse = settings.getType().getField("reverseHands"); seated = settings.getType().getField("seated");
            running = Class.forName("org.vivecraft.client_vr.VRState").getField("VR_RUNNING");
            head = data.getField("hmd"); controller = data.getMethod("getController", int.class); eye = data.getMethod("getEye", passType);
            origin = data.getField("origin"); worldRotation = data.getField("rotation_radians"); worldScale = data.getField("worldScale");
            position = pose.getMethod("getPositionF"); worldPosition = pose.getMethod("getPosition"); direction = pose.getMethod("getDirection"); matrix = pose.getMethod("getMatrix");
            tracking = provider.getType().getMethod("isControllerTracking", int.class);
            headTracking = provider.getType().getMethod("isHMDTracking");
            eyeView = Class.forName("org.vivecraft.client_vr.render.helpers.RenderHelper").getMethod("getVRModelView", passType);
            rendering = renderingType.getMethod("instance"); vanilla = renderingType.getMethod("isVanillaRenderPass");
            shadow = Class.forName("org.vivecraft.mod_compat_vr.shaders.ShadersHelper").getMethod("isRenderingShadows");
        }
        Hand hand(Object room, Object provider, int physical, boolean reverse) throws ReflectiveOperationException {
            int logical = reverse ? physical : 1 - physical;
            boolean tracked = (boolean)tracking.invoke(provider, logical);
            Matrix4f aim = new Matrix4f();
            if (tracked) {
                Object pose = controller.invoke(room, logical);
                aim.set((Matrix4fc)matrix.invoke(pose)).setTranslation((Vector3fc)position.invoke(pose));
                tracked = aim.isFinite();
            }
            var trigger = SteamVrMenuInput.trigger(physical); var grip = SteamVrMenuInput.grip(physical);
            return new Hand(tracked, aim, tracked && trigger.active(), tracked && trigger.down(), trigger.changedAt(),
                    tracked && grip.active(), tracked && grip.down(), grip.changedAt());
        }
    }
}
