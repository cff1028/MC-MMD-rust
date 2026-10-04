package com.shiroha.mmdskin.compat.vr.pointer;

import com.shiroha.mmdskin.compat.vr.VivecraftPointerInput;
import com.shiroha.mmdskin.compat.vr.keyboard.VivecraftKeyboardBridge;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorRenderer;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorScenePass;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Vivecraft 1.21.4 render-space poses and actual rendered UI surfaces. No input mutation. */
public final class VivecraftPointerBridge {
    private static final Logger LOGGER = LogManager.getLogger();
    private static boolean initialized, failureLogged;
    private static Bindings bindings;
    private VivecraftPointerBridge() {}

    public record View(Vec3 eye, Matrix4f view, float worldScale) {}
    /** index is Vivecraft's main/off index; physicalLeft also accounts for reverseHands. */
    public record Controller(int index, boolean physicalLeft, Vec3 origin, Vec3 direction,
                             boolean triggerDown, boolean gripDown) {}
    /** Dimensions are full world-space extents, not half sizes or UI pixels. */
    public record UiPlane(String id, Vec3 center, Vec3 right, Vec3 up, double width, double height) {
        public Vec3 normal() { return right.cross(up).normalize(); }
    }
    public record Frame(View view, List<UiPlane> planes, List<Controller> controllers,
                        boolean uiVisible, long frameId) {}

    /** Call after Vivecraft renders its UI planes, when GUI_RENDER_* contains this pass's transform. */
    public static Frame readFrame() {
        if (VrMirrorScenePass.isRendering() || VrMirrorRenderer.isDrawingAvatar()) return null;
        Bindings api = bindings();
        if (api == null) return null;
        try {
            Object client = api.clientInstance.invoke(null);
            if (client == null || !(boolean)api.isVrActive.invoke(client)) return null;
            Object rendering = api.renderingInstance.invoke(null);
            if (rendering == null || (boolean)api.isVanillaPass.invoke(rendering)
                    || (boolean)api.isShadowPass.invoke(null)) return null;
            Object holder = api.holderInstance.invoke(null);
            Object pass = api.currentPass.get(holder);
            if (!(pass instanceof Enum<?> renderPass) || !isEyePass(renderPass.name())) return null;
            Object player = api.vrPlayer.get(holder);
            Object data = player == null ? null : api.worldRender.get(player);
            if (data == null) return null;
            float scale = api.worldScale.getFloat(data);
            float rotation = api.worldRotation.getFloat(data);
            Vec3 roomOrigin = (Vec3)api.worldOrigin.get(data);
            if (!Float.isFinite(scale) || scale <= 0 || !Float.isFinite(rotation) || !finite(roomOrigin)) return null;
            Object eyePose = api.getEye.invoke(data, pass);
            Vec3 eye = (Vec3)api.position.invoke(eyePose);
            Matrix4f view = new Matrix4f((Matrix4fc)api.getModelView.invoke(null, pass));
            if (!finite(eye) || !view.isFinite()) return null;
            View currentView = new View(eye, view, scale);
            Object settings = api.settings.get(holder), provider = api.provider.get(holder);
            boolean reverseHands = api.reverseHands.getBoolean(settings);
            List<Controller> controllers = new ArrayList<>(2);
            for (int hand = 0; hand < 2; hand++) {
                if (provider == null || !(boolean)api.isTracking.invoke(provider, hand)) continue;
                // getController returns c0/c1: Vivecraft aim origin/direction, not h0/h1 grip pose.
                Object pose = api.getController.invoke(data, hand);
                Vec3 origin = (Vec3)api.position.invoke(pose);
                Vec3 direction = vec((Vector3fc)api.direction.invoke(pose));
                if (!finite(origin) || !finite(direction) || direction.lengthSqr() < 1e-12) continue;
                VivecraftPointerInput.State buttons = VivecraftPointerInput.read(provider, hand);
                controllers.add(new Controller(hand, physicalLeft(hand, reverseHands), origin, direction.normalize(),
                        buttons.triggerDown(), buttons.gripDown()));
            }
            List<UiPlane> planes = new ArrayList<>();
            Object bow = api.bowTracker.get(holder);
            boolean bowDrawing = bow != null && (boolean)api.bowDrawing.invoke(bow);
            if (!bowDrawing) {
                Minecraft mc = Minecraft.getInstance();
                boolean radial = (boolean)api.radialShowing.invoke(null);
                boolean keyboard = api.keyboardShowing.getBoolean(null);
                double aspect = (double)mc.getWindow().getGuiScaledHeight() / mc.getWindow().getGuiScaledWidth();
                // Main HUD alone is not an interactive UI. A Screen is, even with hideGui enabled.
                if (mc.screen != null && !com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.isOpen()
                        && !radial && api.guiFramebuffer.get(null) != null) {
                    UiPlane plane = roomPlane("gui", api.guiRenderPos.get(null), api.guiRenderRotation.get(null),
                            api.guiOffset.get(null), api.guiScaleApplied.getFloat(null), aspect, roomOrigin, rotation, scale);
                    if (plane != null) planes.add(plane);
                }
                if (keyboard) {
                    if (api.physicalKeyboard.getBoolean(settings) && !VivecraftKeyboardBridge.isActive()) {
                        api.physicalKeyboardPlanes(planes, roomOrigin, rotation, scale);
                    } else if (api.keyboardFramebuffer.get(null) != null) {
                        UiPlane plane = roomPlane("keyboard", api.keyboardPos.get(null), api.keyboardRotation.get(null),
                                null, VivecraftKeyboardBridge.isActive() ? VivecraftKeyboardBridge.KEYBOARD_SCALE
                                        : api.guiScale.getFloat(null), aspect, roomOrigin, rotation, scale);
                        if (plane != null) planes.add(plane);
                    }
                }
                if (radial && !(boolean)api.inMenuRoom.invoke(null) && api.radialFramebuffer.get(null) != null) {
                    UiPlane plane = roomPlane("radial", api.radialPos.get(null), api.radialRotation.get(null),
                            null, api.guiScale.getFloat(null), aspect, roomOrigin, rotation, scale);
                    if (plane != null) planes.add(plane);
                }
            }
            Matrix4f spatial = com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.renderPanelPose();
            if (spatial != null) {
                float width = com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.panelWidth();
                float height = com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.panelHeight();
                UiPlane plane = roomPlane("spatial-menu", spatial.getTranslation(new Vector3f()),
                        new Matrix4f(spatial).setTranslation(0, 0, 0), null, width / 1.5f,
                        (double)height / width, roomOrigin, rotation, scale);
                if (plane != null) planes.add(plane);
            }
            return new Frame(currentView, List.copyOf(planes), List.copyOf(controllers), !planes.isEmpty(),
                    ((Number)api.frameId.get(holder)).longValue());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failure(failure);
            return null;
        }
    }

    static boolean isEyePass(String pass) { return "LEFT".equals(pass) || "RIGHT".equals(pass) || "CENTER".equals(pass); }
    static boolean physicalLeft(int index, boolean reverse) { return (index == 1) != reverse; }

    static UiPlane roomPlane(String id, Object roomPosition, Object roomRotation, Object localOffset,
                             float guiScale, double aspect, Vec3 origin, float rotation, float worldScale) {
        if (!(roomPosition instanceof Vector3fc position) || !(roomRotation instanceof Matrix4fc roomMatrix)
                || !Float.isFinite(guiScale) || guiScale <= 0 || !Double.isFinite(aspect) || aspect <= 0) return null;
        Matrix4f worldMatrix = new Matrix4f().rotationY(rotation).mul(roomMatrix);
        Vec3 center = roomToWorld(position, origin, rotation, worldScale);
        if (localOffset instanceof Vector3fc offset) {
            // GUI_OFFSET_LOCAL is unscaled room metres and precedes GUI_SCALE in applyGUIModelView.
            center = center.add(vec(worldMatrix.transformDirection(offset, new Vector3f()).mul(worldScale)));
        }
        Vec3 right = vec(worldMatrix.transformDirection(1, 0, 0, new Vector3f())).normalize();
        Vec3 up = vec(worldMatrix.transformDirection(0, 1, 0, new Vector3f())).normalize();
        double width = 1.5 * guiScale * worldScale;
        if (!finite(center) || !finite(right) || !finite(up) || right.lengthSqr() < .5 || up.lengthSqr() < .5) return null;
        return new UiPlane(id, center, right, up, width, width * aspect);
    }

    static Vec3 roomToWorld(Vector3fc room, Vec3 origin, float rotation, float scale) {
        return origin.add(vec(new Vector3f(room).mul(scale).rotateY(rotation)));
    }
    private static Vec3 vec(Vector3fc value) { return new Vec3(value.x(), value.y(), value.z()); }
    private static boolean finite(Vec3 value) { return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z); }

    private static Bindings bindings() {
        if (!initialized) {
            initialized = true;
            try { bindings = new Bindings(); }
            catch (ClassNotFoundException absent) { /* Optional Vivecraft dependency. */ }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure(failure); }
        }
        return bindings;
    }
    private static void failure(Throwable cause) {
        if (!failureLogged) { failureLogged = true; LOGGER.warn("Unable to read Vivecraft visual pointer frame", cause); }
    }

    private static final class Bindings {
        final Method clientInstance, isVrActive, renderingInstance, isVanillaPass, isShadowPass;
        final Method holderInstance, getEye, getController, position, direction, getModelView, isTracking;
        final Method radialShowing, inMenuRoom, bowDrawing;
        final Field currentPass, vrPlayer, worldRender, worldScale, worldRotation, worldOrigin, settings, provider, reverseHands, frameId, bowTracker;
        final Field guiRenderPos, guiRenderRotation, guiOffset, guiScaleApplied, guiScale, guiFramebuffer;
        final Field keyboardPos, keyboardRotation, keyboardShowing, keyboardFramebuffer, physicalKeyboard, keyboard;
        final Field radialPos, radialRotation, radialFramebuffer;
        final Field keyboardKeys;
        final Method keyboardCenter, keyRenderBounds;

        Bindings() throws ReflectiveOperationException {
            Class<?> client = Class.forName("org.vivecraft.api.client.VRClientAPI");
            Class<?> rendering = Class.forName("org.vivecraft.api.client.VRRenderingAPI");
            Class<?> holder = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
            Class<?> data = Class.forName("org.vivecraft.client_vr.VRData");
            Class<?> pose = Class.forName("org.vivecraft.client_vr.VRData$VRDevicePose");
            Class<?> pass = Class.forName("org.vivecraft.api.client.data.RenderPass");
            Class<?> gui = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler");
            Class<?> kb = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler");
            Class<?> radial = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.RadialHandler");
            clientInstance=client.getMethod("instance"); isVrActive=client.getMethod("isVRActive");
            renderingInstance=rendering.getMethod("instance"); isVanillaPass=rendering.getMethod("isVanillaRenderPass");
            isShadowPass=Class.forName("org.vivecraft.mod_compat_vr.shaders.ShadersHelper").getMethod("isRenderingShadows");
            inMenuRoom=Class.forName("org.vivecraft.client_vr.MethodHolder").getMethod("isInMenuRoom");
            holderInstance=holder.getMethod("getInstance"); currentPass=holder.getField("currentPass");
            vrPlayer=holder.getField("vrPlayer"); worldRender=vrPlayer.getType().getField("vrdata_world_render");
            settings=holder.getField("vrSettings"); provider=holder.getField("vr"); frameId=holder.getField("frameIndex");
            reverseHands=settings.getType().getField("reverseHands"); physicalKeyboard=settings.getType().getField("physicalKeyboard");
            bowTracker=holder.getField("bowTracker"); bowDrawing=bowTracker.getType().getMethod("isDrawing");
            worldScale=data.getField("worldScale"); worldRotation=data.getField("rotation_radians"); worldOrigin=data.getField("origin");
            getEye=data.getMethod("getEye",pass); getController=data.getMethod("getController",int.class);
            position=pose.getMethod("getPosition"); direction=pose.getMethod("getDirection");
            getModelView=Class.forName("org.vivecraft.client_vr.render.helpers.RenderHelper").getMethod("getVRModelView",pass);
            isTracking=provider.getType().getMethod("isControllerTracking",int.class);
            guiRenderPos=gui.getField("GUI_RENDER_POS_ROOM"); guiRenderRotation=gui.getField("GUI_RENDER_ROTATION_ROOM");
            guiOffset=gui.getField("GUI_OFFSET_LOCAL"); guiScaleApplied=gui.getField("GUI_SCALE_APPLIED"); guiScale=gui.getField("GUI_SCALE"); guiFramebuffer=gui.getField("GUI_FRAMEBUFFER");
            keyboardPos=kb.getField("POS_ROOM"); keyboardRotation=kb.getField("ROTATION_ROOM"); keyboardShowing=kb.getField("SHOWING"); keyboardFramebuffer=kb.getField("FRAMEBUFFER");
            keyboard=kb.getField("PHYSICAL_KEYBOARD"); keyboardKeys=keyboard.getType().getDeclaredField("keys"); keyboardKeys.setAccessible(true);
            keyboardCenter=keyboard.getType().getDeclaredMethod("getCenterPos"); keyboardCenter.setAccessible(true);
            keyRenderBounds=Class.forName("org.vivecraft.client_vr.gui.PhysicalKeyboard$KeyButton").getDeclaredMethod("getRenderBoundingBox"); keyRenderBounds.setAccessible(true);
            radialShowing=radial.getMethod("isShowing"); radialPos=radial.getField("POS_ROOM"); radialRotation=radial.getField("ROTATION_ROOM"); radialFramebuffer=radial.getField("FRAMEBUFFER");
        }

        void physicalKeyboardPlanes(List<UiPlane> planes, Vec3 origin, float rotation, float scale) throws ReflectiveOperationException {
            Object instance=keyboard.get(null);
            if (instance == null || !(keyboardKeys.get(instance) instanceof List<?> keys)) return;
            Vector3fc roomPos=(Vector3fc)keyboardPos.get(null), localCenter=(Vector3fc)keyboardCenter.invoke(instance);
            Matrix4f matrix=new Matrix4f().rotationY(rotation).mul((Matrix4fc)keyboardRotation.get(null));
            Vec3 worldPos=roomToWorld(roomPos,origin,rotation,scale);
            Vec3 right=vec(matrix.transformDirection(1,0,0,new Vector3f())).normalize();
            // Physical keyboard coordinates have increasing Y down the rows.
            Vec3 up=vec(matrix.transformDirection(0,-1,0,new Vector3f())).normalize();
            int index=0;
            for(Object key:keys) {
                if (!(keyRenderBounds.invoke(key) instanceof AABB box)) continue;
                Vector3f local=new Vector3f((float)((box.minX+box.maxX)*.5), (float)((box.minY+box.maxY)*.5), (float)box.minZ).sub(localCenter);
                Vec3 center=worldPos.add(vec(matrix.transformDirection(local).mul(scale)));
                double width=(box.maxX-box.minX)*scale, height=(box.maxY-box.minY)*scale;
                if(finite(center)&&width>0&&height>0)planes.add(new UiPlane("keyboard-key-"+(index++),center,right,up,width,height));
            }
        }
    }
}
