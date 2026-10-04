package com.shiroha.mmdskin.compat.vr.keyboard;

import com.shiroha.mmdskin.compat.vr.VivecraftMirrorInput;
import com.shiroha.mmdskin.compat.vr.VivecraftPointerInput;
import com.shiroha.mmdskin.compat.vr.VivecraftUiInteraction;
import com.shiroha.mmdskin.compat.vr.SteamVrUiInput;
import com.shiroha.mmdskin.compat.vr.hand.SteamVrFingerTracking;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.config.VrKeyboardMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.apache.logging.log4j.LogManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Optional Vivecraft overlay adapter. All three typing methods share one key/IME dispatcher. */
public final class VivecraftKeyboardBridge {
    public static final float KEYBOARD_SCALE = .60f;
    private static final Object INPUT_OWNER = new Object();
    private static final HandState[] HANDS = {new HandState(), new HandState()};
    private static final VrKeyboardKeyState KEYS = new VrKeyboardKeyState();
    private static final VrKeyboardGrabState GRAB = new VrKeyboardGrabState();
    private static Api api;
    private static boolean initialized, broken, opened, previousEnabled;
    private static Screen target;

    /** Pure configuration check; safe from optional mixins during Vivecraft class initialization. */
    public static boolean isActive() { return !broken && ConfigManager.isVRKeyboardEnabled(); }

    public static boolean isShowing() {
        if (!isActive()) return false;
        Api access = api();
        try { return access != null && access.showing.getBoolean(null); }
        catch (ReflectiveOperationException failure) { fail(failure); return false; }
    }

    public static boolean isDragging() { return opened && GRAB.owner() >= 0; }

    public static boolean isPointingOrDragging(int physicalHand) {
        if (!opened) return false;
        if (isDragging()) return true;
        for (int logical = 0; logical < 2; logical++)
            if (VivecraftUiInteraction.physicalHand(logical) == physicalHand) return HANDS[logical].pointed;
        return false;
    }

    public static boolean isKeyboardFramebuffer(Object framebuffer) {
        if (!isActive() || framebuffer == null) return false;
        Api access = api();
        try { return access != null && access.framebuffer.get(null) == framebuffer; }
        catch (ReflectiveOperationException failure) { fail(failure); return false; }
    }

    public static void hide() {
        Api access = api();
        try { if (access != null) access.showOverlay.invoke(null, false); }
        catch (ReflectiveOperationException failure) { fail(failure); }
        close();
    }

    /** A new text receiver must not inherit a held trigger, finger contact or backspace repeat. */
    public static void onInputTargetChanged() {
        resetHands();
        VrKeyboardUi.setHover(0, null, false); VrKeyboardUi.setHover(1, null, false);
        VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, false);
    }

    /** Called by GuiKeyboard's render injection; keeps the original framebuffer and projection. */
    public static boolean render(Object screen, GuiGraphics graphics) {
        if (!isActive() || !(screen instanceof Screen ui)) return false;
        VrKeyboardUi.render(graphics, ui.width, ui.height);
        return true;
    }

    public static void overlayChanged() {
        Api access = api();
        if (access == null) return;
        try {
            if (isActive() && access.showing.getBoolean(null)) open(); else close();
        } catch (ReflectiveOperationException failure) { fail(failure); }
    }

    /** A reachable, tilted plane. Vivecraft continues to own all overlay show/hide/reorientation calls. */
    public static boolean orientOverlay(boolean guiRelative) {
        if (!isActive()) return false;
        Api access = api();
        if (access == null) return false;
        try {
            if (!access.showing.getBoolean(null)) return true;
            Object holder = access.holder.invoke(null);
            Object player = access.player.get(holder);
            Object room = player == null ? null : access.roomPre.get(player);
            if (room == null) return false;
            Object head = access.hmd.get(room);
            Vector3f headPosition = new Vector3f((Vector3fc)access.position.invoke(head));
            Vector3f direction = new Vector3f((Vector3fc)access.direction.invoke(head));
            direction.y = 0;
            if (!direction.isFinite() || direction.lengthSquared() < 1e-6f) direction.set(0, 0, -1);
            direction.normalize();
            Vector3f center = headPosition.add(direction.mul(.65f)).add(0, -.43f, 0);
            float yaw = (float)(Math.PI + Math.atan2(direction.x, direction.z));
            access.positionRoom.set(null, center);
            access.rotationRoom.set(null, new Matrix4f().rotationY(yaw).rotateX(-.35f));
            access.forGui.setBoolean(null, guiRelative);
            resetHands();
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) { fail(failure); return false; }
    }

    /** Invoked from KeyboardHandler.processGui (also runs in the VR menu room, without a world). */
    public static boolean processGui() {
        Api access = api();
        if (access == null) return false;
        try {
            boolean enabled = isActive();
            if (enabled != previousEnabled) {
                previousEnabled = enabled;
                close();
                if (enabled) access.unpressPhysical.invoke(access.physicalKeyboard.get(null));
                if (access.showing.getBoolean(null)) {
                    access.reinit.invoke(null);
                    access.orient.invoke(null, Minecraft.getInstance().screen != null);
                }
            }
            if (!enabled) return false;
            boolean showing = access.showing.getBoolean(null);
            Object holder = access.holder.invoke(null), settings = access.settings.get(holder);
            Object provider = access.provider.get(holder), player = access.player.get(holder);
            Object room = player == null ? null : access.roomPre.get(player);
            if (!showing || provider == null || room == null || access.seated.getBoolean(settings)
                    || !access.vrRunning.getBoolean(null)) {
                close();
                access.pointedLeft.setBoolean(null, false); access.pointedRight.setBoolean(null, false);
                return true;
            }
            open();
            VrKeyboardUi.tick();
            if (!isShowing() || target != Minecraft.getInstance().screen) {
                close();
                access.pointedLeft.setBoolean(null, false); access.pointedRight.setBoolean(null, false);
                return true;
            }
            Screen ui = (Screen)access.ui.get(null);
            Vector3fc center = (Vector3fc)access.positionRoom.get(null);
            Matrix4fc rotation = (Matrix4fc)access.rotationRoom.get(null);
            if (center == null || rotation == null) return true;
            Matrix4fc[] poses = new Matrix4fc[2];
            boolean[] rayHits = new boolean[2];
            for (int logical = 0; logical < 2; logical++) {
                if (!(boolean)access.isTracking.invoke(provider, logical)) continue;
                int side = VivecraftUiInteraction.physicalHand(logical);
                Object pose = access.controller.invoke(room, logical);
                poses[side] = new Matrix4f((Matrix4fc)access.matrix.invoke(pose))
                        .setTranslation((Vector3fc)access.position.invoke(pose));
                Vector2fc uv = (Vector2fc)access.cursor.invoke(null, center, rotation, KEYBOARD_SCALE, pose);
                rayHits[side] = Float.isFinite(uv.x()) && Float.isFinite(uv.y())
                        && uv.x() >= 0 && uv.x() <= 1 && uv.y() >= 0 && uv.y() <= 1;
            }
            var leftGrip = SteamVrUiInput.grip(0); var rightGrip = SteamVrUiInput.grip(1);
            Matrix4f moved = GRAB.update(new Matrix4f(rotation).setTranslation(center), poses[0], poses[1], rayHits[0], rayHits[1],
                    leftGrip.down(), rightGrip.down(), leftGrip.changedAt(), rightGrip.changedAt(),
                    ConfigManager.isVRKeyboardDragSmoothing(), ConfigManager.getVRKeyboardDragPositionMs(),
                    ConfigManager.getVRKeyboardDragRotationMs(), System.nanoTime());
            if (moved != null) {
                access.positionRoom.set(null, moved.getTranslation(new Vector3f()));
                access.rotationRoom.set(null, new Matrix4f(moved).setTranslation(0, 0, 0));
                resetTyping();
                VrKeyboardUi.setHover(0, null, false); VrKeyboardUi.setHover(1, null, false);
                access.pointedRight.setBoolean(null, true); access.pointedLeft.setBoolean(null, true);
                VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, true, true);
                return true;
            }
            Matrix4f inverse = new Matrix4f(rotation).invert();
            double width = 1.5 * KEYBOARD_SCALE;
            double height = width * (double)Minecraft.getInstance().getWindow().getGuiScaledHeight()
                    / Minecraft.getInstance().getWindow().getGuiScaledWidth();
            long now = System.nanoTime();
            boolean capture = false;
            List<String> activeKeys = new ArrayList<>(), pressedKeys = new ArrayList<>();
            Map<String, Integer> keyHands = new HashMap<>();
            for (int index = 0; index < 2; index++) {
                HandState hand = HANDS[index];
                if (!(boolean)access.isTracking.invoke(provider, index)) {
                    hand.reset(); VrKeyboardUi.setHover(index, null, false); continue;
                }
                Object pose = access.controller.invoke(room, index);
                Vector3fc aim = (Vector3fc)access.position.invoke(pose);
                Vector3f[] tips = SteamVrFingerTracking.readRoomTips(provider, index, aim);
                VrKeyboardContact.Point body = VrKeyboardContact.project(aim, center, inverse, width, height);
                VrKeyboardContact.Point[] fingers = new VrKeyboardContact.Point[5];
                boolean fingerNear = false;
                if (tips != null) for (int finger = 0; finger < fingers.length; finger++) {
                    fingers[finger] = VrKeyboardContact.project(tips[finger], center, inverse, width, height);
                    fingerNear |= fingers[finger] != null && fingers[finger].near(.16);
                }
                VrKeyboardMode mode = chooseMode(ConfigManager.getVRKeyboardMode(), tips != null,
                        fingerNear, body != null && body.near(.15));
                if (hand.mode != mode) { hand.reset(); hand.mode = mode; }
                String hovered = null;
                boolean held = false, pointing = false;
                if (mode == VrKeyboardMode.RAY) {
                    Vector2fc uv = (Vector2fc)access.cursor.invoke(null, center, rotation, KEYBOARD_SCALE, pose);
                    hovered = VrKeyboardUi.hit(uv.x(), uv.y(), ui.width, ui.height);
                    boolean down = VivecraftPointerInput.read(provider, index).triggerDown()
                            && VivecraftUiInteraction.canTypeOnKeyboard(VivecraftUiInteraction.physicalHand(index));
                    String press = hand.ray.update(hovered, down, !down,
                            false, now);
                    // The panel owns the trigger even over its text strip or gaps. Otherwise an
                    // overlay opened without a Screen could send those presses to the world.
                    pointing = Float.isFinite(uv.x()) && Float.isFinite(uv.y())
                            && uv.x() >= 0 && uv.x() <= 1 && uv.y() >= 0 && uv.y() <= 1;
                    held = hand.ray.isHeld(hovered);
                    if (held) { activeKeys.add(hovered); keyHands.putIfAbsent(hovered, index); }
                    if (press != null) pressedKeys.add(press);
                } else if (mode == VrKeyboardMode.HAND) {
                    Touch touch = touch(hand.contacts[0], body, .045, .075, ui.width, ui.height, now);
                    hovered = touch.key(); held = touch.held();
                    if (held) { activeKeys.add(hovered); keyHands.putIfAbsent(hovered, index); }
                    if (touch.pressed() != null) pressedKeys.add(touch.pressed());
                    pointing = body != null && body.near(.15);
                } else {
                    for (int finger = 0; finger < fingers.length; finger++) {
                        Touch touch = touch(hand.contacts[finger], fingers[finger], .006, .025, ui.width, ui.height, now);
                        String key = touch.key();
                        if (key != null && (hovered == null || touch.held())) hovered = key;
                        held |= touch.held();
                        if (touch.held()) { activeKeys.add(key); keyHands.putIfAbsent(key, index); }
                        if (touch.pressed() != null) pressedKeys.add(touch.pressed());
                    }
                    pointing = fingerNear;
                }
                hand.pointed = pointing;
                capture |= pointing || held;
                VrKeyboardUi.setHover(index, hovered, held);
            }
            // Several fingertips, hands or input modes can touch the same physical key. Only
            // transition it once, and repeat backspace once, until every owner has lifted.
            for (String key : KEYS.update(activeKeys, pressedKeys, VrKeyboardUi::isRepeatable, now)) {
                VrKeyboardUi.press(key);
                haptic(access, provider, keyHands.getOrDefault(key, 0));
                if (!isShowing() || target != Minecraft.getInstance().screen) {
                    resetHands(); capture = false; break;
                }
            }
            access.pointedRight.setBoolean(null, HANDS[0].pointed);
            access.pointedLeft.setBoolean(null, HANDS[1].pointed);
            VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, capture);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            fail(failure); return false;
        }
    }

    static VrKeyboardMode chooseMode(VrKeyboardMode requested, boolean skeletal, boolean fingerNear, boolean handNear) {
        if (requested != VrKeyboardMode.AUTO) return requested;
        if (skeletal && fingerNear) return VrKeyboardMode.FINGER;
        return handNear ? VrKeyboardMode.HAND : VrKeyboardMode.RAY;
    }

    private record Touch(String key, boolean held, String pressed) {
        private static final Touch NONE = new Touch(null, false, null);
    }

    private static Touch touch(VrKeyboardPressState state, VrKeyboardContact.Point point,
                                double pressDepth, double releaseDepth, int width, int height, long now) {
        if (point == null) { state.reset(); return Touch.NONE; }
        String key = point.onPlane() ? VrKeyboardUi.hit(point.u(), point.v(), width, height) : null;
        if (point.depth() < -.08) { state.reset(); return Touch.NONE; }
        String pressed = state.update(key, point.depth() <= pressDepth,
                point.depth() > releaseDepth, false, now);
        return point.near(.15) ? new Touch(key, state.isHeld(key), pressed) : Touch.NONE;
    }

    public static void discardOriginalBindings() {
        Api access = api();
        if (access == null || !isActive()) return;
        try {
            Object click = access.keyboardClick.get(null), shift = access.keyboardShift.get(null);
            for (Object controller : access.controllerTypes) while ((boolean)access.consumeHanded.invoke(click, controller)) { }
            // Our on-keyboard shift is latched; do not leave stale original keyboard events on disable.
            while (shift instanceof KeyMapping key && key.consumeClick()) { }
        } catch (ReflectiveOperationException failure) { fail(failure); }
    }

    private static void open() {
        Screen current = Minecraft.getInstance().screen;
        if (opened && target == current) return;
        close(); target = current; opened = true; resetHands(); VrKeyboardUi.onOpen();
    }
    private static void close() {
        if (opened) VrKeyboardUi.onClose();
        opened = false; target = null; resetHands();
        VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, false);
    }
    private static void resetHands() { resetTyping(); GRAB.reset(); }
    private static void resetTyping() { for (HandState hand : HANDS) hand.reset(); KEYS.reset(); }
    private static void haptic(Api access, Object provider, int index) {
        try { access.haptic.invoke(provider, index, 200); } catch (ReflectiveOperationException ignored) { }
    }
    private static void fail(Throwable failure) {
        if (!broken) LogManager.getLogger().warn("[VR keyboard] Custom keyboard integration unavailable; restoring Vivecraft keyboard", failure);
        broken = true; close();
    }
    private static Api api() {
        if (!initialized) {
            initialized = true;
            try { api = new Api(); }
            catch (ClassNotFoundException absent) { /* Optional mod. */ }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { fail(failure); }
        }
        return api;
    }
    private static final class HandState {
        VrKeyboardMode mode;
        boolean pointed;
        final VrKeyboardPressState ray = new VrKeyboardPressState();
        final VrKeyboardPressState[] contacts = {new VrKeyboardPressState(), new VrKeyboardPressState(),
                new VrKeyboardPressState(), new VrKeyboardPressState(), new VrKeyboardPressState()};
        void reset() { mode = null; pointed = false; ray.reset(); for (var contact : contacts) contact.reset(); }
    }
    private static final class Api {
        final Method holder, showOverlay, reinit, orient, position, direction, matrix, controller, cursor,
                isTracking, haptic, consumeHanded, unpressPhysical;
        final Field showing, positionRoom, rotationRoom, forGui, ui, framebuffer, pointedLeft, pointedRight,
                player, provider, settings, seated, roomPre, hmd, keyboardClick, keyboardShift, vrRunning, physicalKeyboard;
        final Object[] controllerTypes;

        Api() throws ReflectiveOperationException {
            Class<?> keyboard = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler");
            Class<?> dataHolder = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
            Class<?> gui = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler");
            Class<?> data = Class.forName("org.vivecraft.client_vr.VRData");
            Class<?> pose = Class.forName("org.vivecraft.client_vr.VRData$VRDevicePose");
            Class<?> controllerType = Class.forName("org.vivecraft.client_vr.provider.ControllerType");
            showing = keyboard.getField("SHOWING"); positionRoom = keyboard.getField("POS_ROOM");
            rotationRoom = keyboard.getField("ROTATION_ROOM"); forGui = keyboard.getField("KEYBOARD_FOR_GUI");
            ui = keyboard.getField("UI"); framebuffer = keyboard.getField("FRAMEBUFFER");
            physicalKeyboard = keyboard.getField("PHYSICAL_KEYBOARD");
            unpressPhysical = physicalKeyboard.getType().getMethod("unpressAllKeys");
            pointedLeft = privateField(keyboard, "POINTED_L"); pointedRight = privateField(keyboard, "POINTED_R");
            showOverlay = keyboard.getMethod("setOverlayShowing", boolean.class); reinit = keyboard.getMethod("reinitKeyboard");
            orient = keyboard.getMethod("orientOverlay", boolean.class);
            holder = dataHolder.getMethod("getInstance"); player = dataHolder.getField("vrPlayer");
            settings = dataHolder.getField("vrSettings"); seated = settings.getType().getField("seated");
            provider = dataHolder.getField("vr"); roomPre = player.getType().getField("vrdata_room_pre");
            vrRunning = Class.forName("org.vivecraft.client_vr.VRState").getField("VR_RUNNING");
            hmd = data.getField("hmd"); position = pose.getMethod("getPositionF"); direction = pose.getMethod("getDirection");
            matrix = pose.getMethod("getMatrix");
            controller = data.getMethod("getController", int.class);
            cursor = gui.getMethod("getTexCoordsForCursor", Vector3f.class, Matrix4f.class, float.class, pose);
            isTracking = provider.getType().getMethod("isControllerTracking", int.class);
            haptic = provider.getType().getMethod("triggerHapticPulse", int.class, int.class);
            keyboardClick = gui.getField("KEY_KEYBOARD_CLICK"); keyboardShift = gui.getField("KEY_KEYBOARD_SHIFT");
            consumeHanded = keyboardClick.getType().getMethod("consumeClick", controllerType);
            controllerTypes = controllerType.getEnumConstants();
        }

        private static Field privateField(Class<?> type, String name) throws ReflectiveOperationException {
            Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
        }
    }
    private VivecraftKeyboardBridge() {}
}
