package com.shiroha.mmdskin.compat.vr.mirror;

import com.shiroha.mmdskin.compat.vr.VRArmHider;
import com.shiroha.mmdskin.compat.vr.VRDataProvider;
import com.shiroha.mmdskin.compat.vr.VivecraftMirrorInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** A client-only mirror with controller grab and physical quality controls. */
public final class VrMirrorController {
    public enum Mode { OFF, LOW, HIGH }
    private static final Object INPUT_OWNER = new Object();
    private static VrMirrorGeometry mirror;
    private static VrMirrorGrab grab;
    private static ClientLevel level;
    private static Mode mode = Mode.OFF;
    private static final VrMirrorInteractionEdges edges = new VrMirrorInteractionEdges();
    private static final boolean[] hoveredControls = new boolean[Mode.values().length];
    private static boolean hovered;

    private VrMirrorController() {}

    public static boolean isEnabled() { return mirror != null && mode != Mode.OFF; }
    public static VrMirrorGeometry geometry() { return mirror; }
    public static boolean isHighlighted() { return hovered || grab != null; }
    public static boolean isGrabbed() { return grab != null; }
    public static Mode mode() { return mode; }
    public static boolean isControlHovered(Mode candidate) { return hoveredControls[candidate.ordinal()]; }

    public static void setMode(Mode next) {
        if (next == null) return;
        if (next == Mode.OFF) reset();
        else if (mirror != null) mode = next;
    }

    public static void toggle() {
        if (isEnabled()) { reset(); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !VRArmHider.isLocalVrRuntimeActive()) return;
        float[] pose = VRDataProvider.getRenderTrackingData(mc.player);
        if (pose == null || pose.length < 7) return;
        Vec3 head = new Vec3(pose[0], pose[1], pose[2]);
        Quaternionf orientation = new Quaternionf(pose[3], pose[4], pose[5], pose[6]);
        Vec3 forward = VrMirrorGeometry.rotate(new Vec3(0, 0, -1), orientation);
        forward = new Vec3(forward.x, 0, forward.z).normalize();
        if (forward.lengthSqr() < 0.1) forward = new Vec3(0, 0, -1);
        Vec3 feet = VRDataProvider.getRenderOrigin(mc.player, 1);
        Vec3 center = new Vec3(head.x, feet.y + VrMirrorGeometry.HEIGHT / 2, head.z).add(forward.scale(2));
        mirror = new VrMirrorGeometry(center, new Quaternionf().rotationY((float) Math.atan2(-forward.x, -forward.z)));
        mode = Mode.LOW;
        level = mc.level;
        primeButtons();
        mc.player.displayClientMessage(Component.translatableWithFallback("message.mmdskin.vr_mirror.controls",
                "镜子已开启：快捷栏切换键抓取移动，扳机点击旁边 OFF / LOW / HIGH。"), true);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (isEnabled() && (mc.level != level || mc.player == null || !VRArmHider.isLocalVrRuntimeActive())) reset();
        VivecraftMirrorInput.refreshPriorities();
    }

    /** Runs before Vivecraft binding consumers and per eye for smooth dragging. */
    public static void updateInteraction() {
        if (mirror == null) return;
        Minecraft mc = Minecraft.getInstance();
        hovered = false;
        java.util.Arrays.fill(hoveredControls, false);
        if (mc.screen != null || mc.player == null || mc.level != level) {
            grab = null;
            VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, false);
            primeButtons();
            return;
        }
        var controllers = new VivecraftMirrorInput.ControllerPose[2];
        var presses = new VrMirrorInteractionEdges.Presses[2];
        var controls = new Mode[2];
        for (int hand = 0; hand < 2; hand++) {
            controllers[hand] = VivecraftMirrorInput.readController(hand);
            boolean down = controllers[hand] != null && controllers[hand].gripDown();
            presses[hand] = edges.sample(hand, down, VivecraftMirrorInput.readTrigger(hand));
            if (grab != null && grab.hand() == hand) {
                if (down) mirror = grab.move(controllers[hand].position(), controllers[hand].rotation());
                else grab = null;
            }
        }
        boolean controlHover = false;
        for (int hand = 0; hand < 2; hand++) {
            var controller = controllers[hand];
            if (controller == null || grab != null) continue;
            Vec3 direction = VrMirrorGeometry.rotate(new Vec3(0, 0, -1), controller.rotation());
            boolean hit = mirror.hitDistance(controller.position(), direction, 6) >= 0;
            hovered |= hit;
            controls[hand] = VrMirrorControls.hit(mirror, controller.position(), direction, 6);
            if (controls[hand] != null) {
                hoveredControls[controls[hand].ordinal()] = true;
                controlHover = true;
            }
            if (hit && presses[hand].grab()) grab = VrMirrorGrab.begin(hand, mirror, controller.position(), controller.rotation());
        }
        // Capture queued vanilla actions before accepting a click or closing the mirror.
        VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, hovered || grab != null,
                hovered || grab != null || controlHover);
        for (int hand = 0; hand < 2; hand++) {
            if (controls[hand] != null && presses[hand].click()
                    && !VivecraftMirrorInput.isTriggerCapturedByOther(INPUT_OWNER)) {
                setMode(controls[hand]);
                break;
            }
        }
    }

    private static void primeButtons() {
        for (int hand = 0; hand < 2; hand++) {
            var controller = VivecraftMirrorInput.readController(hand);
            edges.prime(hand, controller != null && controller.gripDown(), VivecraftMirrorInput.readTrigger(hand));
        }
    }

    public static void reset() {
        VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, false);
        mirror = null;
        grab = null;
        level = null;
        mode = Mode.OFF;
        hovered = false;
        java.util.Arrays.fill(hoveredControls, false);
        VrMirrorRenderer.release();
        VrMirrorControls.release();
    }
}
