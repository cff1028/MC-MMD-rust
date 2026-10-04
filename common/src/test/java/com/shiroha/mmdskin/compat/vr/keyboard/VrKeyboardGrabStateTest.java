package com.shiroha.mmdskin.compat.vr.keyboard;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VrKeyboardGrabStateTest {
    private final Matrix4f panel = new Matrix4f().translation(0, 1, -1).rotateX(-.35f);
    private final Matrix4f hand = new Matrix4f().translation(.3f, 1, -.3f).rotateY(.2f);

    @Test void grabbingDoesNotSnapAndFollowsTranslationAndRotation() {
        var s = new VrKeyboardGrabState();
        assertNull(s.update(panel, hand, hand, true, true, false, false, 0, 0));
        assertTrue(panel.equals(s.update(panel, hand, hand, true, true, true, false, 1, 0), 1e-5f));
        Matrix4f movedHand = new Matrix4f().translation(-.2f, 1.2f, -.5f).rotateY(.7f);
        Matrix4f expected = new Matrix4f(movedHand).mul(new Matrix4f(hand).invert()).mul(panel);
        // Once grabbed, the ray may leave the plane without dropping the keyboard.
        assertTrue(expected.equals(s.update(panel, movedHand, hand, false, false, true, true, 1, 2), 1e-5f));
        assertEquals(0, s.owner());
        assertNull(s.update(panel, movedHand, hand, true, true, false, true, 1, 2));
        assertEquals(-1, s.owner());
    }
    @Test void heldGripOnOpenCannotGrabUntilReleasedAndPressedOnThePanel() {
        var s = new VrKeyboardGrabState();
        assertNull(s.update(panel, hand, hand, true, true, true, false, 1, 0));
        assertNull(s.update(panel, hand, hand, true, true, true, false, 1, 0));
        s.update(panel, hand, hand, false, false, false, false, 1, 0);
        assertNull(s.update(panel, hand, hand, false, false, true, false, 2, 0));
        assertNull(s.update(panel, hand, hand, true, true, true, false, 2, 0));
        s.update(panel, hand, hand, true, true, false, false, 2, 0);
        assertNotNull(s.update(panel, hand, hand, true, true, true, false, 3, 0));
    }
    @Test void trackingLossAndInvalidTransformsReleaseWithoutTeleportOrHandOff() {
        var s = new VrKeyboardGrabState();
        s.update(panel, hand, hand, true, true, false, false, 0, 0);
        s.update(panel, hand, hand, true, true, true, true, 1, 2);
        assertNull(s.update(panel, null, hand, true, true, true, true, 1, 2));
        assertNull(s.update(panel, hand, hand, true, true, true, true, 1, 2));
        s.reset(); s.update(panel, hand, hand, true, true, false, false, 0, 0);
        assertNull(s.update(panel, new Matrix4f().zero(), hand, true, false, true, false, 1, 0));
    }

    @Test void wristRollNeverRotatesOrOrbitsTheKeyboard() {
        var s = new VrKeyboardGrabState();
        Matrix4f tilted = new Matrix4f(hand).rotateX(.6f).rotateZ(.4f);
        s.update(panel, tilted, hand, true, false, false, false, 0, 0);
        assertTrue(panel.equals(s.update(panel, tilted, hand, true, false, true, false, 1, 0), 1e-5f));
        Matrix4f rolled = new Matrix4f(tilted).rotateZ(1.8f);
        assertTrue(panel.equals(s.update(panel, rolled, hand, false, false, true, false, 1, 0), 1e-5f));
    }

    @Test void combinedYawPitchChangesKeepTheTopEdgeHorizontalAndPreserveScale() {
        var s = new VrKeyboardGrabState();
        s.update(panel, hand, hand, true, false, false, false, 0, 0);
        s.update(panel, hand, hand, true, false, true, false, 1, 0);
        Matrix4f turned = new Matrix4f().translation(.5f, 1.2f, -.6f).rotateY(1.2f).rotateX(.55f).rotateZ(.8f);
        Matrix4f result = s.update(panel, turned, hand, false, false, true, false, 1, 0);
        Matrix4f expected = new Matrix4f().rotationY(1).rotateX(.2f);
        assertTrue(expected.equals(new Matrix4f(result).setTranslation(0, 0, 0), 1e-5f));
        assertEquals(0, result.m01(), 1e-6f); // local horizontal axis has no vertical component
        assertEquals(1, result.determinant(), 1e-5f);
    }

    private Matrix4f smooth(VrKeyboardGrabState s, Matrix4f panel, Matrix4f pose, boolean down, long now, int ms) {
        return s.update(panel, pose, hand, true, false, down, false, 1, 0, true, ms, ms, now);
    }
    private VrKeyboardGrabState smoothGrab() {
        var s = new VrKeyboardGrabState();
        smooth(s, panel, hand, false, 0, 200);
        smooth(s, panel, hand, true, 0, 200);
        return s;
    }

    @Test void smoothingHasTheSameResponseAtDifferentFrameRatesAndNoRoll() {
        Matrix4f targetHand = new Matrix4f(hand).translateLocal(1, .5f, 0).rotateX(.4f).rotateY(.6f);
        Matrix4f[] results = new Matrix4f[2];
        int[] frames = {12, 24}; // 60 and 120 Hz over 200 ms
        for (int i = 0; i < frames.length; i++) {
            var s = smoothGrab();
            for (int frame = 1; frame <= frames[i]; frame++) {
                results[i] = smooth(s, panel, targetHand, true, 200_000_000L * frame / frames[i], 200);
                assertEquals(0, results[i].m01(), 1e-6f);
            }
        }
        assertTrue(results[0].equals(results[1], 1e-5f));
        var immediate = smoothGrab();
        Matrix4f target = smooth(immediate, panel, targetHand, true, 1, 0);
        Vector3f expected = panel.getTranslation(new Vector3f()).lerp(target.getTranslation(new Vector3f()), .95f);
        assertEquals(0, expected.distance(results[0].getTranslation(new Vector3f())), 1e-5f);
    }

    @Test void releaseStopsAtVisiblePoseAndRegrabDoesNotSnapToPreviousTarget() {
        var s = smoothGrab();
        Matrix4f movedHand = new Matrix4f(hand).translateLocal(1, 0, 0);
        Matrix4f displayed = smooth(s, panel, movedHand, true, 20_000_000, 200);
        assertTrue(displayed.m30() > 0 && displayed.m30() < 1);
        assertNull(smooth(s, displayed, movedHand, false, 30_000_000, 200));
        assertNull(smooth(s, displayed, hand, false, 40_000_000, 200));
        assertTrue(displayed.equals(smooth(s, displayed, hand, true, 50_000_000, 200), 1e-5f));
    }

    @Test void smoothingUsesShortYawPathAcrossTheWrapBoundary() {
        var s = new VrKeyboardGrabState();
        Matrix4f backPanel = new Matrix4f().translation(0, 1, -1).rotateY((float)Math.toRadians(179));
        smooth(s, backPanel, hand, false, 0, 200);
        smooth(s, backPanel, hand, true, 0, 200);
        Matrix4f result = smooth(s, backPanel, new Matrix4f(hand).rotateY((float)Math.toRadians(4)), true, 40_000_000, 200);
        assertTrue(result.m22() < -.99f, "Must rotate through 180 degrees, not toward zero");
    }
}
