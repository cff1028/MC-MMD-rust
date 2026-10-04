package com.shiroha.mmdskin.ui.spatial;

import com.shiroha.mmdskin.compat.vr.VivecraftMirrorInput;
import com.shiroha.mmdskin.compat.vr.VrTriggerFocusState;
import com.shiroha.mmdskin.compat.vr.keyboard.VivecraftKeyboardBridge;
import com.shiroha.mmdskin.ui.spatial.backend.GameMenuBackend;
import com.shiroha.mmdskin.ui.spatial.lumen.input.InputState;
import com.shiroha.mmdskin.ui.spatial.lumen.ui.MenuApp;
import com.shiroha.mmdskin.ui.spatial.lumen.ui.SurfaceSpec;
import com.shiroha.mmdskin.ui.spatial.render.SpatialMenuCanvasTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** One menu update after each VR poll, one shared texture for all eye passes. */
public final class SpatialMenuHost {
    private static final Object CAPTURE = new Object();
    private static final SpatialMenuTransforms.ForwardGesture OPEN = new SpatialMenuTransforms.ForwardGesture();
    private static final SpatialMenuTransforms.DetailMotion DETAIL = new SpatialMenuTransforms.DetailMotion();
    private static final SpatialMenuTransforms.DetailMotion DISTANCE_PREVIEW = new SpatialMenuTransforms.DetailMotion();
    private static final VrTriggerFocusState FOCUS = new VrTriggerFocusState();
    private static final InputState INPUT = new InputState();
    private static final SpatialMenuCanvasTarget TARGET = new SpatialMenuCanvasTarget();
    private static MenuApp app;
    private static GameMenuBackend backend;
    private static MenuScreen screen;
    private static Screen parent;
    private static Screen nativeRootScreen;
    private static Matrix4f panel;
    private static SurfaceSpec spec;
    private static SpatialMenuVrBridge.Frame lastFrame;
    private static long lastPoll = Long.MIN_VALUE, lastTime;
    private static Object level;
    private static boolean waitRelease, scrollArmed, textInput, nativeSession, closing, failureLogged;
    private static int nativeFontDepth;
    private SpatialMenuHost() {}

    public static boolean isOpen() { return screen != null && Minecraft.getInstance().screen == screen && app != null; }
    public static boolean isNativeSession() { return nativeSession && Minecraft.getInstance().screen != null && !isOpen(); }
    public static boolean nativeFontActive() { return nativeFontDepth > 0; }
    public static void beginNativeFont() { nativeFontDepth++; }
    public static void endNativeFont() { nativeFontDepth = Math.max(0, nativeFontDepth - 1); }
    public static Matrix4f panelPose() { return isOpen() && panel != null ? new Matrix4f(panel) : null; }
    public static Matrix4f renderPanelPose() {
        if (!isOpen() || panel == null) return null;
        return spec.anchor() == SurfaceSpec.Anchor.RIGHT_HAND ? new Matrix4f(panel)
                : DETAIL.roomPose(SpatialMenuVrBridge.renderWorldFrame());
    }
    public static Matrix4f renderDistancePreviewPose() {
        return isOpen() && app.panelDistancePreview() != null
                ? DISTANCE_PREVIEW.roomPose(SpatialMenuVrBridge.renderWorldFrame()) : null;
    }
    public static float panelWidth() { return spec == null ? 0 : spec.recommendedWidthMetres() * (float)Math.clamp(app.panelScale(), .5, 2); }
    public static float panelHeight() { return spec == null ? 0 : panelWidth() * spec.height() / spec.width(); }

    public static void poll() {
        try {
            var frame = SpatialMenuVrBridge.pollFrame();
            if (frame == null) { if (screen != null) close(); OPEN.reset(); lastPoll = Long.MIN_VALUE; lastTime = 0; return; }
            if (frame.frameId() == lastPoll) return;
            lastPoll = frame.frameId(); lastFrame = frame;
            Minecraft mc = Minecraft.getInstance();
            if (nativeSession && (mc.screen == null || mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)
                    && !com.shiroha.mmdskin.compat.vr.VrCalibrationController.isActive()) nativeSession = false;
            boolean opening = OPEN.update(frame.right().tracked(), frame.rightStickX(), frame.rightStickY());
            if (mc.getOverlay() != null) return;
            if (!isOpen()) {
                VivecraftMirrorInput.setInteractionPriority(CAPTURE, false, false);
                // The same stick scrolls specialist editors; do not nest another menu over them.
                if (opening && !nativeSession) openQuick(frame);
                else return;
            }
            if (level != mc.level) { close(); return; }
            if (nativeSession) { backend.refresh(); nativeSession = false; }
            long now = System.nanoTime();
            double motionSeconds = lastTime == 0 ? 0 : Math.max(0, (now - lastTime) / 1e9); lastTime = now;
            float dt = (float)Math.min(.1, motionSeconds);
            if (panel == null || !app.surfaceSpec().equals(spec)) {
                position(frame, app.surfaceSpec()); resetSurfaceInput();
            } else if (spec.anchor() == SurfaceSpec.Anchor.RIGHT_HAND) {
                if (frame.right().tracked()) position(frame, spec);
            } else if (!updateDetail(frame, motionSeconds)) { close(); return; }
            var left = frame.left(); var right = frame.right();
            SpatialMenuGeometry.Hit[] hit = {
                left.tracked() ? SpatialMenuGeometry.intersect(panel, panelWidth(), panelHeight(), left.aim()) : null,
                right.tracked() ? SpatialMenuGeometry.intersect(panel, panelWidth(), panelHeight(), right.aim()) : null
            };
            for (int hand = 0; hand < 2; hand++) if (hit[hand] != null && keyboardInFront(hand == 0 ? left : right, hit[hand].distance())) hit[hand] = null;
            if (waitRelease) {
                FOCUS.reset(); app.cancelPointer();
                // The menu action set only becomes active on the poll after opening.
                // An inactive sample is not evidence that a held trigger was released.
                if ((left.triggerActive() || right.triggerActive()) && !left.triggerDown() && !right.triggerDown()) waitRelease = false;
            }
            var update = FOCUS.update(left.triggerDown(), right.triggerDown(),
                    !waitRelease && left.tracked() && left.triggerActive() && (INPUT.down && FOCUS.focus() == 0 || hit[0] != null && hit[0].inside()),
                    !waitRelease && right.tracked() && right.triggerActive() && (INPUT.down && FOCUS.focus() == 1 || hit[1] != null && hit[1].inside()),
                    left.triggerChangedAt(), right.triggerChangedAt());
            var active = hit[update.focus()];
            INPUT.x = active == null ? -10000 : spec.x() + active.u() * spec.width();
            INPUT.y = active == null ? -10000 : spec.y() + active.v() * spec.height();
            INPUT.pressed = update.pressed(); INPUT.released = update.released(); INPUT.down = update.owner() >= 0;
            var focusedHand = update.focus() == 0 ? left : right;
            if (!focusedHand.tracked() || !focusedHand.triggerActive()) { app.cancelPointer(); INPUT.released = false; }
            if (Math.abs(frame.rightStickY()) < .3f) scrollArmed = true;
            if (scrollArmed && Math.abs(frame.rightStickY()) > .25f) INPUT.scroll = frame.rightStickY() * dt * 9;
            VivecraftMirrorInput.setInteractionPriority(CAPTURE, true, true);
            boolean previewing = app.panelDistancePreview() != null;
            paint(dt);
            if (isOpen() && app.surfaceSpec().anchor() == SurfaceSpec.Anchor.HEAD_STABLE) {
                Double distance = app.panelDistancePreview();
                if (distance != null || previewing && INPUT.released) {
                    DISTANCE_PREVIEW.previewDistanceFrom(DETAIL, frame.world(), frame.headPosition(),
                            frame.headDirection(), distance != null ? distance : backend.number("ui.panelDistance"));
                    if (distance == null) DETAIL.acceptDistancePreview(DISTANCE_PREVIEW);
                }
                if (distance == null) DISTANCE_PREVIEW.reset();
            }
            INPUT.endFrame();
            if (!isOpen()) return; // an action may open a real Screen or start a world
            if (app.screenName().equals("closed")) { close(); return; }
            if (!app.surfaceSpec().equals(spec)) {
                position(frame, app.surfaceSpec()); resetSurfaceInput();
                paint(0); // repaint the new layout, no input/animation advance
            }
            boolean wantsText = app.wantsTextInput();
            if (wantsText != textInput) { textInput = wantsText; showKeyboard(wantsText); }
        } catch (RuntimeException | LinkageError e) {
            if (!failureLogged) { failureLogged = true; LogManager.getLogger().error("[Spatial UI] Menu failed; releasing input", e); }
            close();
        }
    }
    private static void paint(float dt) {
        backend.beginFrame();
        try { TARGET.render(app, INPUT, dt); }
        finally { backend.endFrame(); }
    }
    private static void openQuick(SpatialMenuVrBridge.Frame frame) {
        Minecraft mc = Minecraft.getInstance(); parent = mc.screen; level = mc.level;
        if (backend == null) { backend = new GameMenuBackend(SpatialMenuHost::close); app = new MenuApp(backend); }
        backend.refresh(); app.openQuick(); screen = new MenuScreen(); mc.setScreen(screen);
        position(frame, app.surfaceSpec()); FOCUS.reset(); INPUT.endFrame(); INPUT.down = false;
        waitRelease = true; scrollArmed = false; lastTime = 0;
        try { Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.RadialHandler").getMethod("setOverlayShowing", boolean.class, Class.forName("org.vivecraft.client_vr.provider.ControllerType")).invoke(null, false, null); }
        catch (ReflectiveOperationException ignored) {}
    }
    private static void position(SpatialMenuVrBridge.Frame frame, SurfaceSpec next) {
        spec = next;
        DETAIL.reset(); DISTANCE_PREVIEW.reset();
        if (next.anchor() != SurfaceSpec.Anchor.RIGHT_HAND) { updateDetail(frame, 0); return; }
        Vector3f forward = new Vector3f(frame.headDirection().x, 0, frame.headDirection().z);
        if (forward.lengthSquared() < 1e-6f) forward.set(0, 0, -1); else forward.normalize();
        Vector3f center = frame.right().aim().getTranslation(new Vector3f()).add(new Vector3f(forward).mul(.32f)).add(0, .12f, 0);
        panel = SpatialMenuGeometry.faceHead(center, frame.headPosition());
    }
    private static boolean updateDetail(SpatialMenuVrBridge.Frame frame, double seconds) {
        if (app.panelDistancePreview() != null) {
            // Freeze in world space while editing, including in the two follow modes.
            panel = DETAIL.roomPose(frame.world());
            return panel != null;
        }
        boolean reduced = backend.bool("ui.reducedMotion");
        if (!DETAIL.update(frame.world(), frame.headPosition(), frame.headDirection(),
                SpatialMenuTransforms.DetailBehavior.parse(backend.string("ui.detailBehavior")),
                backend.number("ui.panelDistance"), backend.number("ui.closeDistance"),
                reduced ? 0 : backend.number("ui.positionSmoothingMs"),
                reduced ? 0 : backend.number("ui.rotationSmoothingMs"), seconds)) return false;
        panel = DETAIL.roomPose(frame.world());
        return panel != null;
    }
    private static void resetSurfaceInput() {
        FOCUS.reset(); app.cancelPointer(); waitRelease = true; INPUT.down = false;
        INPUT.pressed = INPUT.released = false;
    }
    public static void render() {
        if (!isOpen() || panel == null) return;
        try {
            Matrix4f renderPanel = renderPanelPose();
            if (renderPanel == null) return;
            Matrix4f preview = renderDistancePreviewPose();
            if (preview != null) {
                Matrix4f previewMvp = SpatialMenuVrBridge.renderMatrix(preview, panelWidth(), panelHeight());
                // Non-interactive ghost behind the controls, regardless of the chosen distance.
                if (previewMvp != null) TARGET.drawDistancePreview(previewMvp, spec);
            }
            Matrix4f mvp = SpatialMenuVrBridge.renderMatrix(renderPanel, panelWidth(), panelHeight());
            if (mvp != null) TARGET.draw(mvp, spec);
        }
        catch (RuntimeException | LinkageError e) { LogManager.getLogger().error("[Spatial UI] Unable to draw menu", e); close(); }
    }
    public static void openNative(Screen next) {
        if (next == null) return;
        if (app != null) app.cancelPointer();
        DISTANCE_PREVIEW.reset();
        if (backend != null) backend.flush();
        nativeSession = true; nativeRootScreen = next; panel = null; DETAIL.reset(); FOCUS.reset(); INPUT.down = false; INPUT.endFrame();
        showKeyboard(false); textInput = false; VivecraftMirrorInput.setInteractionPriority(CAPTURE, false, false);
        Minecraft.getInstance().setScreen(next);
    }
    /** Only the specialist editor opened by this menu inherits our return destination. */
    public static boolean returnFromNativeRoot(Screen closingScreen) {
        Minecraft mc = Minecraft.getInstance();
        if (!nativeSession || screen == null || closingScreen != nativeRootScreen
                || mc.screen != closingScreen || level != mc.level) return false;
        mc.setScreen(screen);
        nativeRootScreen = null;
        return true;
    }
    public static void close() {
        if (closing) return; closing = true;
        try {
            if (app != null) app.cancelPointer();
            DISTANCE_PREVIEW.reset();
            if (backend != null) backend.menuClosed();
            if (backend != null) backend.flush();
            showKeyboard(false); textInput = false;
            VivecraftMirrorInput.setInteractionPriority(CAPTURE, false, false);
            if (isOpen()) Minecraft.getInstance().setScreen(parent);
            screen = null; nativeRootScreen = null; panel = null; DETAIL.reset(); lastTime = 0;
            nativeSession = false; FOCUS.reset(); INPUT.down = false; INPUT.endFrame();
        } finally { closing = false; }
    }
    public static void shutdown() {
        close(); if (app != null) app.close(); app = null; backend = null; TARGET.close();
        spec = null; parent = null; level = null; lastFrame = null; lastPoll = Long.MIN_VALUE; lastTime = 0;
        nativeFontDepth = 0; OPEN.reset(); SpatialMenuVrBridge.reset();
    }
    private static void showKeyboard(boolean visible) {
        try { Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler").getMethod("setOverlayShowing", boolean.class).invoke(null, visible); }
        catch (ReflectiveOperationException ignored) {}
    }
    private static boolean keyboardInFront(SpatialMenuVrBridge.Hand hand, float menuDistance) {
        try {
            Class<?> kb = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler");
            if (!kb.getField("SHOWING").getBoolean(null)) return false;
            var pose = new Matrix4f((Matrix4f)kb.getField("ROTATION_ROOM").get(null)).setTranslation((Vector3f)kb.getField("POS_ROOM").get(null));
            var window = Minecraft.getInstance().getWindow();
            var hit = SpatialMenuGeometry.intersect(pose, .9f, .9f * window.getGuiScaledHeight() / window.getGuiScaledWidth(), hand.aim());
            return hit != null && hit.inside() && hit.distance() < menuDistance;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }
    private static final class MenuScreen extends Screen {
        MenuScreen() { super(Component.literal("MMD Skin · Lumen")); }
        @Override public void render(GuiGraphics graphics, int x, int y, float delta) {}
        @Override public void renderBackground(GuiGraphics graphics, int x, int y, float delta) {}
        @Override public boolean isPauseScreen() { return false; }
        @Override public boolean charTyped(char c, int mods) { if (app != null) app.character(c); return true; }
        @Override public boolean keyPressed(int key, int scan, int mods) { if (app != null) app.key(key, org.lwjgl.glfw.GLFW.GLFW_PRESS, mods); return true; }
        @Override public void onClose() { SpatialMenuHost.close(); }
    }
}
