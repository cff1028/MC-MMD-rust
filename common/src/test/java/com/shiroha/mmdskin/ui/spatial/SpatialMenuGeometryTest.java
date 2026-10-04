package com.shiroha.mmdskin.ui.spatial;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialMenuGeometryTest {
    @Test void finitePanelReportsPixelDirectionAndOutsideCoordinatesForDragging() {
        Matrix4f panel = new Matrix4f().translation(0, 0, -2);
        var center = SpatialMenuGeometry.intersect(panel, 2, 1, new Matrix4f());
        assertNotNull(center); assertTrue(center.inside()); assertEquals(.5, center.u(), 1e-6); assertEquals(.5, center.v(), 1e-6);
        assertEquals(2, center.distance(), 1e-6);
        var upperRight = SpatialMenuGeometry.intersect(panel, 2, 1, new Matrix4f().translation(.5f, .25f, 0));
        assertTrue(upperRight.inside()); assertEquals(.75, upperRight.u(), 1e-6); assertEquals(.25, upperRight.v(), 1e-6);
        var outside = SpatialMenuGeometry.intersect(panel, 2, 1, new Matrix4f().translation(2, 0, 0));
        assertNotNull(outside); assertFalse(outside.inside()); assertEquals(1.5, outside.u(), 1e-6);
    }

    @Test void backFacesParallelRaysAndBeyondReachAreNotInteractive() {
        assertNull(SpatialMenuGeometry.intersect(new Matrix4f().translation(0, 0, 2), 2, 1, new Matrix4f().rotationY((float)Math.PI)));
        assertNull(SpatialMenuGeometry.intersect(new Matrix4f().translation(0, 0, -2), 2, 1, new Matrix4f().rotationY((float)Math.PI / 2)));
        assertNull(SpatialMenuGeometry.intersect(new Matrix4f().translation(0, 0, -9), 2, 1, new Matrix4f()));
        assertNull(SpatialMenuGeometry.intersect(new Matrix4f().translation(0, 0, 2), 2, 1, new Matrix4f()));
    }

    @Test void invalidTransformsAndDimensionsNeverProduceAnActionableHit() {
        Matrix4f panel = new Matrix4f().translation(0, 0, -2);
        assertNull(SpatialMenuGeometry.intersect(panel, Float.NaN, 1, new Matrix4f()));
        assertNull(SpatialMenuGeometry.intersect(panel, 1, Float.POSITIVE_INFINITY, new Matrix4f()));
        assertNull(SpatialMenuGeometry.intersect(panel, -1, 1, new Matrix4f()));
        assertNull(SpatialMenuGeometry.intersect(new Matrix4f().scale(0), 1, 1, new Matrix4f()));
        assertNull(SpatialMenuGeometry.intersect(panel, 1, 1, new Matrix4f().m00(Float.NaN)));
    }

    @Test void scaledPanelPreservesDistanceInRoomMetres() {
        Matrix4f panel = new Matrix4f().translation(0, 0, -2).scale(2);
        var hit = SpatialMenuGeometry.intersect(panel, 1, 1, new Matrix4f().translation(.5f, 0, 0));
        assertNotNull(hit); assertTrue(hit.inside()); assertEquals(.75, hit.u(), 1e-6); assertEquals(2, hit.distance(), 1e-6);
    }

    @Test void facingHeadKeepsPanelUprightAndRayMatchesItsCenter() {
        Vector3f center = new Vector3f(.4f, .8f, -1.2f), head = new Vector3f(0, 1.6f, 0);
        Matrix4f panel = SpatialMenuGeometry.faceHead(center, head);
        Vector3f normal = panel.transformDirection(0, 0, 1, new Vector3f());
        assertEquals(1, normal.dot(new Vector3f(head).sub(center).normalize()), .0001);
        Vector3f right = panel.transformDirection(1, 0, 0, new Vector3f());
        assertEquals(0, right.y, .0001);
        Matrix4f aim = new Matrix4f(panel).setTranslation(head);
        var hit = SpatialMenuGeometry.intersect(panel, 1, 1, aim);
        assertNotNull(hit); assertEquals(.5, hit.u(), .0001); assertEquals(.5, hit.v(), .0001);
        assertEquals(center.distance(head), hit.distance(), .0001);
    }
}
