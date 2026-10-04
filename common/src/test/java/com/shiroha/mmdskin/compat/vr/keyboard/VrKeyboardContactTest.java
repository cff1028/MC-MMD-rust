package com.shiroha.mmdskin.compat.vr.keyboard;

import com.shiroha.mmdskin.config.VrKeyboardMode;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VrKeyboardContactTest {
    @Test void tiltedMovedPlaneHasSameKeyCoordinatesAsTheRenderedTexture() {
        var center = new Vector3f(4, 1.2f, -6);
        var rotation = new Matrix4f().rotationY(.85f).rotateX(-.35f);
        var point = rotation.transformDirection(new Vector3f(.3f, .1f, .012f)).add(center);
        var hit = VrKeyboardContact.project(point, center, new Matrix4f(rotation).invert(), .9, .45);
        assertNotNull(hit);
        assertEquals(.5 + .3 / .9, hit.u(), 1e-5);
        assertEquals(.5 - .1 / .45, hit.v(), 1e-5);
        assertEquals(.012, hit.depth(), 1e-5);
        assertTrue(hit.near(.02));
    }

    @Test void farBehindAndOutsidePlanesCannotBecomeContacts() {
        assertFalse(new VrKeyboardContact.Point(.5, .5, -.5).near(.15));
        assertFalse(new VrKeyboardContact.Point(1.1, .5, 0).near(.15));
        assertNull(VrKeyboardContact.project(new Vector3f(Float.NaN), new Vector3f(), new Matrix4f(), .9, .4));
    }

    @Test void automaticModeUsesRealNearbySkeletonThenHandThenRayWhileExplicitModesStayLocked() {
        assertEquals(VrKeyboardMode.FINGER, VivecraftKeyboardBridge.chooseMode(VrKeyboardMode.AUTO, true, true, true));
        assertEquals(VrKeyboardMode.HAND, VivecraftKeyboardBridge.chooseMode(VrKeyboardMode.AUTO, false, true, true));
        assertEquals(VrKeyboardMode.RAY, VivecraftKeyboardBridge.chooseMode(VrKeyboardMode.AUTO, true, false, false));
        assertEquals(VrKeyboardMode.FINGER, VivecraftKeyboardBridge.chooseMode(VrKeyboardMode.FINGER, false, false, true));
        assertEquals(VrKeyboardMode.RAY, VivecraftKeyboardBridge.chooseMode(VrKeyboardMode.RAY, true, true, true));
    }
}
