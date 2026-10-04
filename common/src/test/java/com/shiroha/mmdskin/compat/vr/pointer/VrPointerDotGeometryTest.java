package com.shiroha.mmdskin.compat.vr.pointer;

import org.joml.Matrix4f;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector4d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VrPointerDotGeometryTest {
    private static final Vector3d NORMAL = new Vector3d(0, 0, 1);

    @Test void screenRadiusIsStableAcrossDistanceWorldScaleAndFieldOfView() {
        for (double depth : new double[]{.15, 1, 8, 32, 320}) {
            for (float fov : new float[]{55, 90, 120}) {
                Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(fov), 1.5f, .01f, 1000);
                var disc = VrPointerDotGeometry.create(new Vector3d(0, 0, -depth), NORMAL, projection, 1500, 1000, .003);
                assertCircle(disc, projection, 1500, 1000, 3);
            }
        }
    }

    @Test void tiltedOffAxisStereoPlanesRemainRoundAndOnTheirSurface() {
        for (double eyeShift : new double[]{-.032, .032}) {
            Matrix4f projection = new Matrix4f().frustum(-.07f, .10f, -.09f, .08f, .05f, 100);
            Vector3d center = new Vector3d(.6 + eyeShift, .15, -2);
            Vector3d normal = new Vector3d(.8, .2, .6).normalize();
            var disc = VrPointerDotGeometry.create(center, normal, projection, 2064, 2208, .0045);
            assertCircle(disc, projection, 2064, 2208, 2208 * .0045);
            for (Vector3d point : disc.outer()) assertEquals(0, new Vector3d(point).sub(center).dot(normal), 1e-10);
        }
    }

    @Test void reversedSurfaceNormalAndIndependentInputsProduceTheSameCircle() {
        Matrix4f projection = new Matrix4f().perspective(1.5f, 1, .01f, 100);
        Vector3d center = new Vector3d(.2, .3, -3), normal = new Vector3d(0, 0, -1);
        var disc = VrPointerDotGeometry.create(center, normal, projection, 2048, 2048, .012);
        assertCircle(disc, projection, 2048, 2048, 2048 * .012);
        center.zero(); normal.zero();
        assertEquals(-3, disc.center().z);
    }

    @Test void invalidAndBehindEyeOrEdgeOnGeometryCannotGenerateExplodingVertices() {
        Matrix4f projection = new Matrix4f().perspective(1.5f, 1, .01f, 100);
        assertNull(VrPointerDotGeometry.create(new Vector3d(0, 0, 1), NORMAL, projection, 100, 100, .003));
        assertNull(VrPointerDotGeometry.create(new Vector3d(0, 0, -1), NORMAL, projection, 0, 100, .003));
        assertNull(VrPointerDotGeometry.create(new Vector3d(0, 0, -1), NORMAL, projection, 100, 100, Double.NaN));
        assertNull(VrPointerDotGeometry.create(new Vector3d(0, 0, -1), new Vector3d(1, 0, 0), projection, 100, 100, .003));
        assertNull(VrPointerDotGeometry.create(new Vector3d(Double.NaN, 0, -1), NORMAL, projection, 100, 100, .003));
    }

    private static void assertCircle(VrPointerDotGeometry.Disc disc, Matrix4f projection, int width, int height, double radius) {
        assertNotNull(disc);
        Vector3d center = pixel(disc.center(), projection, width, height);
        for (Vector3d point : disc.outer()) assertEquals(radius, pixel(point, projection, width, height).distance(center), 1e-7);
        for (Vector3d point : disc.inner()) assertEquals(radius * .85, pixel(point, projection, width, height).distance(center), 1e-7);
    }

    private static Vector3d pixel(Vector3dc point, Matrix4f projection, int width, int height) {
        Vector4d clip = new Matrix4d(projection).transform(new Vector4d(point, 1));
        return new Vector3d(clip.x / clip.w * width / 2, clip.y / clip.w * height / 2, 0);
    }
}
