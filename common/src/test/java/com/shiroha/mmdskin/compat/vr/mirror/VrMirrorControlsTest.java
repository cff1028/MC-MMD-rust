package com.shiroha.mmdskin.compat.vr.mirror;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VrMirrorControlsTest {
    @Test
    void eachButtonIsSelectableAfterTheMirrorIsMovedAndRotated() {
        var mirror = new VrMirrorGeometry(new Vec3(4, 2, -3), new Quaternionf().rotationYXZ(.8f, -.25f, .1f));
        for (var mode : VrMirrorController.Mode.values()) {
            Vec3 button = VrMirrorControls.buttonCenter(mirror, mode);
            Vec3 origin = button.add(mirror.normal().scale(2));
            assertEquals(mode, VrMirrorControls.hit(mirror, origin, mirror.normal().scale(-1), 6));
            assertEquals(-1, mirror.hitDistance(origin, mirror.normal().scale(-1), 6),
                    "buttons must not overlap the mirror grab surface");
        }
    }

    @Test
    void buttonsRejectBackFacesGapsAndOutOfReachRays() {
        var mirror = new VrMirrorGeometry(new Vec3(0, 1.1, -2), new Quaternionf());
        Vec3 button = VrMirrorControls.buttonCenter(mirror, VrMirrorController.Mode.LOW);
        assertNull(VrMirrorControls.hit(mirror, button.add(0, 0, -1), new Vec3(0, 0, 1), 6));
        assertNull(VrMirrorControls.hit(mirror, button.add(0, 0, 2), new Vec3(0, 0, -1), 1));
        Vec3 gap = mirror.point(VrMirrorControls.BUTTON_X,
                VrMirrorControls.BUTTON_TOP_Y - VrMirrorControls.BUTTON_SPACING / 2);
        assertNull(VrMirrorControls.hit(mirror, gap.add(0, 0, 1), new Vec3(0, 0, -1), 6));
        assertNull(VrMirrorControls.hit(mirror, button.add(0, 0, 1), new Vec3(Double.NaN, 0, -1), 6));
    }

    @Test
    void onePhysicalPressCannotClickAgainForAnotherEyeOrAnotherMode() {
        var edges = new VrMirrorInteractionEdges();
        edges.prime(0, false, true); // Mirror opened while a trigger was already held.
        assertFalse(edges.sample(0, false, true).click());
        assertFalse(edges.sample(0, false, false).click());
        assertTrue(edges.sample(0, false, true).click());
        assertFalse(edges.sample(0, false, true).click()); // Right-eye render.
        assertFalse(edges.sample(0, false, true).click()); // Ray moved to a different mode.
        edges.sample(0, false, false);
        assertTrue(edges.sample(0, false, true).click());
    }

    @Test
    void gripAndTriggerEdgesRemainIndependentForTheTwoControllers() {
        var edges = new VrMirrorInteractionEdges();
        assertTrue(edges.sample(1, true, false).grab());
        assertFalse(edges.sample(0, false, false).grab());
        var rightPress = edges.sample(0, false, true);
        assertTrue(rightPress.click());
        assertFalse(rightPress.grab());
        assertFalse(edges.sample(1, true, false).grab());
        edges.sample(1, false, false);
        assertTrue(edges.sample(1, true, false).grab());
    }
}
