package com.shiroha.mmdskin.ui.spatial;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialMenuTransformsTest {
    @Test void openRequiresNeutralThenForwardAndDoesNotRepeatWhileHeld() {
        var gesture = new SpatialMenuTransforms.ForwardGesture();
        assertFalse(gesture.update(true, 0, 1)); // Reconnecting with a tilted stick must not open anything.
        assertFalse(gesture.update(true, 0, 0));
        assertTrue(gesture.update(true, 0, 1));
        for (int frame = 0; frame < 120; frame++) assertFalse(gesture.update(true, 0, 1));
        assertFalse(gesture.update(true, 0, .5f));
        assertFalse(gesture.update(true, 0, .8f));
        assertFalse(gesture.update(true, 0, 0));
        assertTrue(gesture.update(true, 0, .75f));
    }

    @Test void horizontalOrBackwardTurningDoesNotOpenAndTrackingLossRearmsSafely() {
        var gesture = new SpatialMenuTransforms.ForwardGesture();
        gesture.update(true, 0, 0);
        assertFalse(gesture.update(true, 1, .8f));
        assertFalse(gesture.update(true, 0, -1));
        assertFalse(gesture.update(false, 0, 0));
        assertFalse(gesture.update(true, 0, 1));
        assertFalse(gesture.update(true, Float.NaN, 0));
        assertFalse(gesture.update(true, 0, 0));
        assertTrue(gesture.update(true, .1f, .8f));
    }

    @Test void roomOriginWorldRotationAndScaleApplyBeforePerEyeView() {
        Matrix4f panel = new Matrix4f().translation(1, 2, -3);
        Matrix4f view = SpatialMenuTransforms.modelView(panel, 2, 1,
                new Vector3f(-10, 5, 7), (float)Math.PI / 2, 3, new Matrix4f());
        Vector3f center = view.transformPosition(0, 0, 0, new Vector3f());
        assertEquals(-19, center.x, .0001); assertEquals(11, center.y, .0001); assertEquals(4, center.z, .0001);
        Vector3f right = view.transformDirection(1, 0, 0, new Vector3f());
        assertEquals(6, right.length(), .0001); assertEquals(-6, right.z, .0001);
        Vector3f up = view.transformDirection(0, 1, 0, new Vector3f());
        assertEquals(3, up.length(), .0001);
    }

    @Test void eyeViewRotatesTranslationAndInvalidDimensionsFailClosed() {
        Matrix4f panel = new Matrix4f().translation(0, 0, -2);
        Matrix4f view = SpatialMenuTransforms.modelView(panel, 1, 1, new Vector3f(), 0, 1,
                new Matrix4f().rotationY((float)Math.PI / 2));
        Vector3f center = view.transformPosition(0, 0, 0, new Vector3f());
        assertEquals(-2, center.x, .0001); assertEquals(0, center.z, .0001);
        assertNull(SpatialMenuTransforms.modelView(panel, 0, 1, new Vector3f(), 0, 1, new Matrix4f()));
        assertNull(SpatialMenuTransforms.modelView(panel, 1, 1, new Vector3f(), 0, Float.NaN, new Matrix4f()));
    }
}
