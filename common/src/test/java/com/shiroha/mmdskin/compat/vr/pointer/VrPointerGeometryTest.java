package com.shiroha.mmdskin.compat.vr.pointer;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VrPointerGeometryTest {
    private static final Vector3d ORIGIN = new Vector3d(), FORWARD = new Vector3d(0, 0, -1);
    private static VrPointerGeometry.Plane plane(String id, double z) {
        return new VrPointerGeometry.Plane(id, new Vector3d(0,0,z), new Vector3d(1,0,0), new Vector3d(0,1,0), 1, .5);
    }

    @Test void rectangleHitsUseMetresAndTopLeftTextureCoordinates() {
        var plane = plane("gui", -2);
        var center = VrPointerGeometry.intersect(ORIGIN, new Vector3d(0,0,-7), plane, 4);
        assertNotNull(center); assertEquals(2, center.distance(), 1e-9);
        assertEquals(.5, center.u(), 1e-9); assertEquals(.5, center.v(), 1e-9);
        var corner = VrPointerGeometry.intersect(new Vector3d(-1,.5,0), FORWARD, plane, 4);
        assertNotNull(corner); assertEquals(0, corner.u()); assertEquals(0, corner.v());
        assertNull(VrPointerGeometry.intersect(new Vector3d(-1.01,.5,0), FORWARD, plane, 4));
        assertNull(VrPointerGeometry.intersect(new Vector3d(0,.51,0), FORWARD, plane, 4));
    }

    @Test void rotatedTranslatedAndNonunitUiBasesRemainPhysicalRectangles() {
        var center = new Vector3d(4,2,8);
        var rectangle = new VrPointerGeometry.Plane("keyboard", center, new Vector3d(0,0,3), new Vector3d(0,2,0), .6, .4);
        center.zero(); // Constructor snapshots the bridge's mutable JOML input.
        var hit = VrPointerGeometry.intersect(new Vector3d(1,2.2,8.3), new Vector3d(4,0,0), rectangle, 5);
        assertNotNull(hit); assertEquals(3, hit.distance(), 1e-9);
        assertEquals(.75, hit.u(), 1e-9); assertEquals(.25, hit.v(), 1e-9);
        assertEquals(4, hit.position().x, 1e-9);
    }

    @Test void parallelBehindBeyondDistanceAndDegenerateRectanglesAreMisses() {
        var plane = plane("gui", -2);
        assertNull(VrPointerGeometry.intersect(ORIGIN, new Vector3d(1,0,0), plane, 5));
        assertNull(VrPointerGeometry.intersect(ORIGIN, new Vector3d(0,0,1), plane, 5));
        assertNull(VrPointerGeometry.intersect(ORIGIN, FORWARD, plane, 1.99));
        assertNotNull(VrPointerGeometry.intersect(ORIGIN, FORWARD, plane, 2));
        assertNull(VrPointerGeometry.intersect(ORIGIN, new Vector3d(), plane, 5));
        assertNull(VrPointerGeometry.intersect(ORIGIN, FORWARD,
                new VrPointerGeometry.Plane("bad",new Vector3d(0,0,-2),new Vector3d(1,0,0),new Vector3d(2,0,0),1,1),5));
    }

    @Test void nearestUsesFiniteBoundsAndStableTiesAcrossAllUiSurfaces() {
        var far = plane("gui", -3); var near = plane("radial", -1); var tied = plane("keyboard", -1);
        var hit = VrPointerGeometry.nearest(ORIGIN, FORWARD, Arrays.asList(far, null, near, tied), 10);
        assertNotNull(hit); assertSame(near, hit.plane());
        assertNull(VrPointerGeometry.nearest(new Vector3d(4,0,0), FORWARD, List.of(far,near), 10));
        assertNull(VrPointerGeometry.nearest(ORIGIN, FORWARD, List.of(near), .5));
    }

    @Test void uiCanBeHitFromEitherSideWithoutTouchingTheInputVectors() {
        var origin = new Vector3d(0,0,-4); var direction = new Vector3d(0,0,2);
        var hit = VrPointerGeometry.intersect(origin,direction,plane("gui",-2),3);
        assertNotNull(hit); assertEquals(2,hit.distance());
        assertEquals(new Vector3d(0,0,-4),origin); assertEquals(new Vector3d(0,0,2),direction);
    }

    @Test void invalidNumbersCannotProduceARenderableHit() {
        var plane = plane("gui",-2);
        assertNull(VrPointerGeometry.intersect(new Vector3d(Double.NaN,0,0),FORWARD,plane,5));
        assertNull(VrPointerGeometry.intersect(ORIGIN,new Vector3d(0,0,Double.NEGATIVE_INFINITY),plane,5));
        assertNull(VrPointerGeometry.intersect(ORIGIN,FORWARD,plane,Double.NaN));
        assertNull(VrPointerGeometry.intersect(ORIGIN,FORWARD,plane,-1));
        assertNull(VrPointerGeometry.intersect(ORIGIN,FORWARD,
                new VrPointerGeometry.Plane("bad",new Vector3d(0,0,-2),new Vector3d(1,0,0),new Vector3d(0,1,0),Double.NaN,1),5));
    }
}
