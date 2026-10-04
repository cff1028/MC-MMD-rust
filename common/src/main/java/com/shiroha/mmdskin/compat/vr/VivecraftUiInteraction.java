package com.shiroha.mmdskin.compat.vr;

import com.shiroha.mmdskin.compat.vr.keyboard.VivecraftKeyboardBridge;
import com.shiroha.mmdskin.config.VrPointerConfigManager;
import com.shiroha.mmdskin.compat.vr.pointer.VrPointerGeometry;
import com.shiroha.mmdskin.compat.vr.pointer.VrPointerMirrorSurfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.apache.logging.log4j.LogManager;
import org.joml.*;
import java.lang.reflect.*;
import java.util.ArrayDeque;

/** One mouse owner for two physical triggers; native Screen input remains handled by MouseHandler. */
public final class VivecraftUiInteraction {
    private static final VrTriggerFocusState FOCUS = new VrTriggerFocusState();
    private static final Object INPUT_OWNER = new Object();
    private static Api api;
    private static boolean initialized, failed, controlsGui, mouseDown, radialClicked;
    private static Object context;
    private static String ownerPlane;
    private static VrTriggerFocusState.Update update = new VrTriggerFocusState.Update(1, -1, false, false);
    private static final Hit[] hits = new Hit[2];
    // Pose polling runs at the headset frame rate; bindings are dispatched at 20 Hz.
    // Retain every edge and its hit position until dispatch, including complete taps between ticks.
    private record ClickEdge(boolean pressed, Hit hit) {}
    private static final ArrayDeque<ClickEdge> pendingClicks = new ArrayDeque<>();
    private static long lastUpdate;
    private static double lastX, lastY;
    private VivecraftUiInteraction() {}
    public record Hit(String plane, float u, float v, float distance) {}

    public static int physicalHand(int logical, boolean reverse) { return (logical == 1) != reverse ? 0 : 1; }
    public static int logicalHand(int physical, boolean reverse) { return reverse ? physical : 1 - physical; }
    public static int physicalHand(int logical) {
        Api a = api();
        try { return a == null ? 1 - logical : physicalHand(logical, a.reverse.getBoolean(a.settings.get(a.holder.invoke(null)))); }
        catch (ReflectiveOperationException e) { return 1 - logical; }
    }
    public static boolean controlsGuiClick() { return controlsGui; }
    public static boolean canTypeOnKeyboard(int physical) {
        return !fresh() || !SteamVrUiInput.trigger(physical).active()
                || update.owner() == physical && "keyboard".equals(ownerPlane);
    }

    /** At GuiHandler.processGui HEAD: own hover movement only when left interaction is enabled. */
    public static boolean processGui() {
        Api a = api();
        if (a == null) return false;
        try {
            Minecraft mc = Minecraft.getInstance();
            Object holder = a.holder.invoke(null), provider = a.provider.get(holder), player = a.player.get(holder);
            Object room = player == null ? null : a.room.get(player);
            boolean radial = (boolean) a.radialShowing.invoke(null);
            boolean keyboard = VivecraftKeyboardBridge.isShowing();
            boolean spatial = com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.isOpen();
            boolean leftEnabled = VrPointerConfigManager.getLiveConfig().leftTriggerInteraction;
            boolean active = a.running.getBoolean(null) && !a.seated.getBoolean(a.settings.get(holder)) && room != null && provider != null;
            boolean available = SteamVrUiInput.trigger(0).active() || SteamVrUiInput.trigger(1).active();
            boolean own = active && available && (radial || keyboard || !spatial && mc.screen != null && leftEnabled);
            Object next = own ? radial ? a.radialUi.get(null) : mc.screen != null ? mc.screen : a.keyboardUi.get(null) : null;
            if (next != context) { release(a); pendingClicks.clear(); context = next; ownerPlane = null; FOCUS.reset(); update = new VrTriggerFocusState.Update(FOCUS.focus(), -1, false, false); radialClicked = false; }
            if (!leftEnabled && !keyboard) FOCUS.useRightHand();
            controlsGui = own && !spatial && !radial && mc.screen != null && leftEnabled;
            if (!own) { lastUpdate = 0; VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, false); return false; }
            boolean reverse = a.reverse.getBoolean(a.settings.get(holder));
            boolean[] tracking = new boolean[2];
            for (int side = 0; side < 2; side++) {
                int logical = logicalHand(side, reverse);
                tracking[side] = (boolean) a.tracking.invoke(provider, logical);
                hits[side] = tracking[side] ? a.nearest(room, logical) : null;
            }
            var left = SteamVrUiInput.trigger(0); var right = SteamVrUiInput.trigger(1);
            update = FOCUS.update(left.down(), right.down(),
                    tracking[0] && (leftEnabled || isPlane(hits[0], "keyboard")) && (update.owner() == 0 || hits[0] != null),
                    tracking[1] && (update.owner() == 1 || hits[1] != null), left.changedAt(), right.changedAt());
            if (update.released()) {
                if ("gui".equals(ownerPlane) || "radial".equals(ownerPlane)) pendingClicks.add(new ClickEdge(false, null));
                ownerPlane = null;
            }
            if (update.pressed()) {
                Hit hit = hits[update.owner()]; ownerPlane = hit.plane;
                if ("gui".equals(ownerPlane) && controlsGui || "radial".equals(ownerPlane))
                    pendingClicks.add(new ClickEdge(true, hit));
            }
            lastUpdate = System.nanoTime();
            VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, radial && (isPlane(hits[0], "radial") || isPlane(hits[1], "radial") || update.owner() >= 0));
            if (controlsGui) {
                Hit hit = hits[update.focus()];
                boolean valid = isPlane(hit, "gui");
                double u = valid ? hit.u : -1, v = valid ? hit.v : -1;
                a.mouseX.setDouble(null, u * mc.getWindow().getWidth()); a.mouseY.setDouble(null, v * mc.getWindow().getHeight());
                a.mouseValid.setBoolean(null, valid);
                // WindowExtension reports the real desktop window even while Vivecraft overrides GUI dimensions.
                moveMouse(a, mc, u, v);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { fail(e); }
        return false;
    }

    /** At processBindingsGui TAIL, after releasing any old Vivecraft-owned mouse press. */
    public static void processGuiButtons() {
        Api a = api(); if (a == null) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!controlsGui) { release(a); return; }
            if (context != mc.screen || !fresh() || VivecraftKeyboardBridge.isDragging()) {
                pendingClicks.clear(); release(a); return;
            }
            ClickEdge edge;
            while ((edge = pendingClicks.poll()) != null) {
                if (!edge.pressed) release(a);
                else if (isPlane(edge.hit, "gui")) {
                    moveMouse(a, mc, edge.hit.u, edge.hit.v);
                    mouseDown = true; a.pressMouse.invoke(null, 0);
                }
                // Never replay queued input into a Screen opened by a previous click.
                if (context != mc.screen) { pendingClicks.clear(); release(a); return; }
            }
            Hit hover = hits[update.focus()];
            moveMouse(a, mc, isPlane(hover, "gui") ? hover.u : -1, isPlane(hover, "gui") ? hover.v : -1);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { fail(e); }
    }

    /** After radial shift handling, before its click/hold-release path. */
    public static boolean processRadialButtons() {
        Api a = api(); if (a == null) return false;
        try {
            if (!(boolean)a.radialShowing.invoke(null)) { radialClicked = false; return false; }
            if (!fresh() || context != a.radialUi.get(null)) { pendingClicks.clear(); return false; }
            Screen ui = (Screen) context;
            boolean handled = radialClicked;
            ClickEdge edge;
            while ((edge = pendingClicks.poll()) != null) {
                if (!edge.pressed) { ui.mouseReleased(lastX, lastY, 0); ui.setDragging(false); }
                else if (isPlane(edge.hit, "radial")) {
                    Hit hit = edge.hit; lastX = hit.u * ui.width; lastY = hit.v * ui.height;
                    radialClicked = true; handled = true;
                    ui.mouseClicked(lastX, lastY, 0);
                    // Keep the overlay open for another trigger click, including after page navigation.
                    VivecraftRadialPages.afterTriggerClick();
                }
                if (context != ui || !(boolean)a.radialShowing.invoke(null)) { pendingClicks.clear(); break; }
            }
            // Once a trigger was used, releasing the menu B key must not select again.
            // A fresh overlay opening resets this latch; the existing B-only interaction still works.
            return handled;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { fail(e); return false; }
    }

    /** Hide the inactive hover cursor on the two-handed radial Screen after its normal pose update. */
    public static void radialCursorUpdated() {
        Api a = api(); if (a == null || !fresh() || !VrPointerConfigManager.getLiveConfig().leftTriggerInteraction) return;
        try {
            if (context != a.radialUi.get(null)) return;
            Object holder = a.holder.invoke(null); boolean reverse = a.reverse.getBoolean(a.settings.get(holder));
            int inactive = 1 - logicalHand(update.focus(), reverse);
            // Click and hover use the same unsmoothed hit, so the button under the ray wins.
            Hit hit = hits[update.focus()];
            (inactive == 0 ? a.cursorX1 : a.cursorX2).setFloat(context, isPlane(hit, "radial") ? hit.u * a.guiWidth.getInt(null) : -1);
            (inactive == 0 ? a.cursorY1 : a.cursorY2).setFloat(context, isPlane(hit, "radial") ? hit.v * a.guiHeight.getInt(null) : -1);
            // Vivecraft cursor 1 is logical off hand, cursor 2 is logical main hand.
            (inactive == 1 ? a.cursorX1 : a.cursorX2).setFloat(context, -1);
            (inactive == 1 ? a.cursorY1 : a.cursorY2).setFloat(context, -1);
        } catch (ReflectiveOperationException e) { fail(e); }
    }
    public static void radialOverlayChanged() { radialClicked = false; context = null; FOCUS.reset(); pendingClicks.clear(); clearEdges(); }

    public static boolean isPointingAtUi() {
        if (com.shiroha.mmdskin.ui.spatial.SpatialMenuVrBridge.isPointingAtPanel()) return true;
        Api a = api(); if (a == null) return false;
        try {
            Object holder = a.holder.invoke(null), provider = a.provider.get(holder), player = a.player.get(holder);
            Object room = player == null ? null : a.room.get(player);
            if (!a.running.getBoolean(null) || room == null || provider == null) return false;
            for (int logical = 0; logical < 2; logical++) if ((boolean)a.tracking.invoke(provider, logical)) {
                if (a.nearest(room, logical) != null) return true;
                Object world = a.world.get(player);
                if (world != null) {
                    Object pose = a.controller.invoke(world, logical);
                    Vector3f p = (Vector3f)a.position.invoke(pose), d = (Vector3f)a.direction.invoke(pose);
                    if (VrPointerGeometry.nearest(new Vector3d(p), new Vector3d(d), VrPointerMirrorSurfaces.current(), 32) != null) return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        return false;
    }
    private static boolean isPlane(Hit hit, String name) { return hit != null && hit.plane.equals(name); }
    private static boolean fresh() { return lastUpdate != 0 && System.nanoTime() - lastUpdate < 500_000_000L; }
    private static void clearEdges() { update = new VrTriggerFocusState.Update(update.focus(), update.owner(), false, false); }
    private static void moveMouse(Api a, Minecraft mc, double u, double v) throws ReflectiveOperationException {
        a.mousePos.invoke(null, u * ((Number)a.actualWidth.invoke(mc.getWindow())).doubleValue(),
                v * ((Number)a.actualHeight.invoke(mc.getWindow())).doubleValue());
    }
    private static void release(Api a) throws ReflectiveOperationException { if (mouseDown) { mouseDown = false; a.releaseMouse.invoke(null, 0); } }
    private static Api api() {
        if (!initialized) { initialized = true; try { api = new Api(); } catch (ClassNotFoundException ignored) {} catch (ReflectiveOperationException | LinkageError e) { fail(e); } }
        return failed ? null : api;
    }
    private static void fail(Throwable e) {
        if (!failed) LogManager.getLogger().warn("[VR UI] UI interaction bridge unavailable; restoring Vivecraft input", e);
        try { if (api != null) release(api); } catch (ReflectiveOperationException ignored) {}
        failed = true; controlsGui = false; context = null; FOCUS.reset(); pendingClicks.clear();
        VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, false);
    }
    private static final class Api {
        final Method holder, controller, position, direction, tracking, cursor, mousePos, pressMouse, releaseMouse, actualWidth, actualHeight, radialShowing;
        final Field player, room, world, guiWidth, guiHeight, provider, settings, reverse, seated, running, guiPos, guiRotation, guiScale,
                keyboardShowing, keyboardPos, keyboardRotation, keyboardUi, radialPos, radialRotation, radialUi,
                mouseX, mouseY, mouseValid, cursorX1, cursorY1, cursorX2, cursorY2;
        Api() throws ReflectiveOperationException {
            Class<?> dh = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR"), gui = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler"),
                    keyboard = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler"), radial = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.RadialHandler"),
                    pose = Class.forName("org.vivecraft.client_vr.VRData$VRDevicePose"), data = Class.forName("org.vivecraft.client_vr.VRData");
            holder = dh.getMethod("getInstance"); player = dh.getField("vrPlayer"); room = player.getType().getField("vrdata_room_pre");
            world = player.getType().getField("vrdata_world_pre"); guiWidth = gui.getField("GUI_WIDTH"); guiHeight = gui.getField("GUI_HEIGHT");
            provider = dh.getField("vr"); settings = dh.getField("vrSettings"); reverse = settings.getType().getField("reverseHands"); seated = settings.getType().getField("seated");
            running = Class.forName("org.vivecraft.client_vr.VRState").getField("VR_RUNNING");
            controller = data.getMethod("getController", int.class); position = pose.getMethod("getPositionF"); direction = pose.getMethod("getDirection"); tracking = provider.getType().getMethod("isControllerTracking", int.class);
            cursor = gui.getMethod("getTexCoordsForCursor", Vector3f.class, Matrix4f.class, float.class, pose);
            guiPos = gui.getField("GUI_POS_ROOM"); guiRotation = gui.getField("GUI_ROTATION_ROOM"); guiScale = gui.getField("GUI_SCALE");
            mouseX = privateField(gui, "CONTROLLER_MOUSE_X"); mouseY = privateField(gui, "CONTROLLER_MOUSE_Y"); mouseValid = privateField(gui, "CONTROLLER_MOUSE_VALID");
            keyboardShowing = keyboard.getField("SHOWING"); keyboardPos = keyboard.getField("POS_ROOM"); keyboardRotation = keyboard.getField("ROTATION_ROOM");
            keyboardUi = keyboard.getField("UI");
            radialShowing = radial.getMethod("isShowing"); radialPos = radial.getField("POS_ROOM"); radialRotation = radial.getField("ROTATION_ROOM"); radialUi = radial.getField("UI");
            Class<?> ui = radialUi.getType(); cursorX1 = ui.getField("cursorX1"); cursorY1 = ui.getField("cursorY1"); cursorX2 = ui.getField("cursorX2"); cursorY2 = ui.getField("cursorY2");
            Class<?> simulator = Class.forName("org.vivecraft.client_vr.provider.InputSimulator"); mousePos = simulator.getMethod("setMousePos", double.class, double.class);
            pressMouse = simulator.getMethod("pressMouse", int.class); releaseMouse = simulator.getMethod("releaseMouse", int.class);
            Class<?> window = Class.forName("org.vivecraft.client_vr.extensions.WindowExtension"); actualWidth = window.getMethod("vivecraft$getActualScreenWidth"); actualHeight = window.getMethod("vivecraft$getActualScreenHeight");
        }
        private static Field privateField(Class<?> type, String name) throws ReflectiveOperationException { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
        Hit nearest(Object room, int logical) throws ReflectiveOperationException {
            Object pose = controller.invoke(room, logical); Hit hit = null;
            if ((boolean) radialShowing.invoke(null)) hit = plane("radial", radialPos.get(null), radialRotation.get(null), guiScale.getFloat(null), pose);
            else if (Minecraft.getInstance().screen != null && !com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.isOpen())
                hit = plane("gui", guiPos.get(null), guiRotation.get(null), guiScale.getFloat(null), pose);
            if (keyboardShowing.getBoolean(null)) {
                Hit keyboard = plane("keyboard", keyboardPos.get(null), keyboardRotation.get(null), VivecraftKeyboardBridge.isActive() ? VivecraftKeyboardBridge.KEYBOARD_SCALE : guiScale.getFloat(null), pose);
                if (keyboard != null && (hit == null || keyboard.distance < hit.distance)) hit = keyboard;
            }
            return hit;
        }
        Hit plane(String name, Object center, Object rotation, float scale, Object pose) throws ReflectiveOperationException {
            if (!(center instanceof Vector3f point) || !(rotation instanceof Matrix4f matrix)) return null;
            Vector2fc uv = (Vector2fc)cursor.invoke(null, point, matrix, scale, pose);
            if (!Float.isFinite(uv.x()) || !Float.isFinite(uv.y()) || uv.x() < 0 || uv.x() > 1 || uv.y() < 0 || uv.y() > 1) return null;
            Vector3f normal = matrix.transformDirection(0, 0, 1, new Vector3f());
            Vector3fc from = (Vector3fc)position.invoke(pose), ray = (Vector3fc)direction.invoke(pose);
            float distance = new Vector3f(point).sub(from).dot(normal) / ray.dot(normal);
            return Float.isFinite(distance) && distance > 0 ? new Hit(name, uv.x(), uv.y(), distance) : null;
        }
    }
}
