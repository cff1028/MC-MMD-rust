package com.shiroha.mmdskin.compat.vr.pointer;

import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorController;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorControls;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorGeometry;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VrPointerMirrorSurfacesTest {
    private static final double EPSILON = 2e-6;
    private static final Vector3d FORWARD = new Vector3d(0, 0, -1);

    @Test void visibleDepthOffsetsKeepMirrorDotsInFrontOfTheReflectionAndLabels() {
        var mirror = new VrMirrorGeometry(new Vec3(0, 1.1, -2), new Quaternionf());
        var surfaces = VrPointerMirrorSurfaces.from(mirror);
        assertEquals(4, surfaces.size());
        var hit = VrPointerGeometry.nearest(new Vector3d(0, 1.1, 0), FORWARD, surfaces, 6);
        assertNotNull(hit);
        assertEquals("mirror", hit.plane().id());
        assertEquals(1.998, hit.distance(), EPSILON);
        assertEquals(-1.998, hit.position().z, EPSILON);
        assertTrue(hit.position().z + .0008 > -2 + VrMirrorGeometry.SURFACE_OFFSET,
                "the dot depth bias must no longer leave it behind the actual mirror texture");
        for (int i = 1; i < surfaces.size(); i++) {
            assertEquals(-2 + VrMirrorControls.LABEL_OFFSET, surfaces.get(i).center().z(), EPSILON);
            assertTrue(surfaces.get(i).center().z() > -2 + VrMirrorControls.SURFACE_OFFSET);
        }
    }

    @Test void translatedAndFullyRotatedSurfacesFollowTheGrabTransform() {
        var mirror = new VrMirrorGeometry(new Vec3(14.25, -31, 8.5), new Quaternionf().rotationYXZ(1.2f, -.4f, .3f));
        Vector3d normal = vector(mirror.normal()).normalize();
        var surfaces = VrPointerMirrorSurfaces.from(mirror);
        for (var plane : surfaces) {
            Vector3d origin = new Vector3d(plane.center()).fma(2, normal);
            var hit = VrPointerGeometry.nearest(origin, new Vector3d(normal).negate(), surfaces, 3);
            assertNotNull(hit);
            assertSame(plane, hit.plane());
            assertEquals(2, hit.distance(), EPSILON);
            assertEquals(.5, hit.u(), EPSILON);
            assertEquals(.5, hit.v(), EPSILON);
            assertTrue(new Vector3d(hit.position()).distance(plane.center()) < EPSILON);
        }
    }

    @Test void everyVisualSurfaceAcceptsRaysFromEitherSide() {
        var mirror = new VrMirrorGeometry(new Vec3(3, 4, 5), new Quaternionf().rotationY(.7f));
        Vector3d normal = vector(mirror.normal()).normalize();
        for (var plane : VrPointerMirrorSurfaces.from(mirror)) for (int side : new int[] {-1, 1}) {
            Vector3d origin = new Vector3d(plane.center()).fma(side * 2, normal);
            var hit = VrPointerGeometry.intersect(origin, new Vector3d(normal).mul(-side * 4), plane, 3);
            assertNotNull(hit, plane.id() + " side=" + side);
            assertEquals(2, hit.distance(), EPSILON);
        }
    }

    @Test void mirrorExtentsExcludeFrameAndButtonsButIncludeExactCorners() {
        var mirror = new VrMirrorGeometry(new Vec3(0, 0, -2), new Quaternionf());
        var plane = VrPointerMirrorSurfaces.from(mirror).getFirst();
        for (int x : new int[] {-1, 1}) for (int y : new int[] {-1, 1}) {
            assertNotNull(VrPointerGeometry.intersect(new Vector3d(x * plane.halfWidth(), y * plane.halfHeight(), 0), FORWARD, plane, 3));
        }
        assertNull(VrPointerGeometry.intersect(new Vector3d(plane.halfWidth() + .001, 0, 0), FORWARD, plane, 3));
        assertNull(VrPointerGeometry.intersect(new Vector3d(0, plane.halfHeight() + .001, 0), FORWARD, plane, 3));
        assertNull(VrPointerGeometry.intersect(vector(VrMirrorControls.buttonCenter(mirror, VrMirrorController.Mode.LOW)).add(0, 0, 2), FORWARD, plane, 3));
    }

    @Test void allThreeModeButtonsUseRenderedPositionsAndFiniteDimensions() {
        var mirror = new VrMirrorGeometry(new Vec3(0, 0, -2), new Quaternionf());
        var surfaces = VrPointerMirrorSurfaces.from(mirror);
        for (int i = 0; i < 3; i++) {
            var mode = VrMirrorController.Mode.values()[i];
            var plane = surfaces.get(i + 1);
            Vec3 rendered = VrMirrorControls.buttonCenter(mirror, mode).add(0, 0, VrMirrorControls.LABEL_OFFSET);
            assertEquals(vector(rendered), plane.center());
            assertEquals("mirror-control-" + mode.name().toLowerCase(java.util.Locale.ROOT), plane.id());
            assertEquals(VrMirrorControls.BUTTON_WIDTH / 2, plane.halfWidth());
            assertEquals(VrMirrorControls.BUTTON_HEIGHT / 2, plane.halfHeight());
            Vector3d origin = new Vector3d(plane.center()).add(0, 0, 2);
            assertSame(plane, VrPointerGeometry.nearest(origin, FORWARD, surfaces, 3).plane());
            assertNull(VrPointerGeometry.intersect(new Vector3d(origin).add(plane.halfWidth() + .001, 0, 0), FORWARD, plane, 3));
            assertNull(VrPointerGeometry.intersect(new Vector3d(origin).add(0, plane.halfHeight() + .001, 0), FORWARD, plane, 3));
        }
        Vector3d gap = new Vector3d(surfaces.get(1).center()).lerp(surfaces.get(2).center(), .5).add(0, 0, 2);
        assertNull(VrPointerGeometry.nearest(gap, FORWARD, surfaces, 3));
    }

    @Test void worldOccluderDistanceAndMaximumRangeStillClipEverySurface() {
        var mirror = new VrMirrorGeometry(new Vec3(0, 0, -2), new Quaternionf());
        var surfaces = VrPointerMirrorSurfaces.from(mirror);
        for (var plane : surfaces) {
            Vector3d origin = new Vector3d(plane.center()).add(0, 0, 2);
            assertNull(VrPointerGeometry.nearest(origin, FORWARD, surfaces, 1.99), "world hit before the mirror must occlude it");
            assertNotNull(VrPointerGeometry.nearest(origin, FORWARD, surfaces, 2));
        }
    }

    @Test void missingMirrorIsEmptyAndNewSnapshotsReflectItsNewPosition() {
        assertTrue(VrPointerMirrorSurfaces.from(null).isEmpty());
        var original = new VrMirrorGeometry(new Vec3(0, 0, -2), new Quaternionf());
        var before = VrPointerMirrorSurfaces.from(original);
        var moved = new VrMirrorGeometry(new Vec3(2, 1, -4), new Quaternionf().rotationY(.3f));
        var after = VrPointerMirrorSurfaces.from(moved);
        assertNotEquals(before.getFirst().center(), after.getFirst().center());
        assertEquals(new Vector3d(0, 0, -1.998), before.getFirst().center());
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
    }

    private static Vector3d vector(Vec3 value) { return new Vector3d(value.x, value.y, value.z); }
}
