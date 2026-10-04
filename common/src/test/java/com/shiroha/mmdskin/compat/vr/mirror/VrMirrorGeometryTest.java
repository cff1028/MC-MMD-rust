package com.shiroha.mmdskin.compat.vr.mirror;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VrMirrorGeometryTest {
    private final VrMirrorGeometry mirror = new VrMirrorGeometry(new Vec3(0, 1.1, -2), new Quaternionf());

    @Test
    void worldPassIsSkippedOutsideTheEyeFrustumAndBeyondReach() {
        var projection = new org.joml.Matrix4f().perspective((float) Math.toRadians(90), 1, .05f, 256);
        Vec3 eye = new Vec3(0, 1.65, 0);
        assertTrue(mirror.isVisibleFrom(eye, new org.joml.Matrix4f(), projection));
        assertFalse(mirror.isVisibleFrom(eye, new org.joml.Matrix4f().rotationY((float) Math.PI), projection));
        var distant = new VrMirrorGeometry(new Vec3(0, 1.1, -60), new Quaternionf());
        assertFalse(distant.isVisibleFrom(eye, new org.joml.Matrix4f(), projection));
    }

    @Test
    void rotatedBoundsContainEveryMirrorCorner() {
        var rotated = new VrMirrorGeometry(new Vec3(2, 3, 4), new Quaternionf().rotationXYZ(.5f, .8f, 1.2f));
        for (int x : new int[] {-1, 1}) for (int y : new int[] {-1, 1}) {
            assertTrue(rotated.bounds().contains(rotated.point(x * VrMirrorGeometry.WIDTH / 2, y * VrMirrorGeometry.HEIGHT / 2)));
        }
    }

    @Test
    void reflectsEachEyeAcrossTheMirrorPlane() {
        for (double x : new double[] {-.032, .032, .4}) {
            Vec3 eye = new Vec3(x, 1.65, 0);
            var reflected = mirror.reflectedView(eye);
            assertNotNull(reflected);
            assertEquals(x, reflected.eye().x, 1e-6);
            assertEquals(eye.y, reflected.eye().y, 1e-6);
            assertEquals(-4, reflected.eye().z, 1e-6);
        }
    }

    @Test
    void asymmetricProjectionMatchesThePhysicalMirrorEdges() {
        var view = mirror.reflectedView(new Vec3(.32, 1.65, -.2));
        var matrix = new org.joml.Matrix4f(view.projection()).mul(view.rotation());
        for (int x : new int[] {-1, 1}) for (int y : new int[] {-1, 1}) {
            Vec3 corner = mirror.point(x * VrMirrorGeometry.WIDTH / 2, y * VrMirrorGeometry.HEIGHT / 2).subtract(view.eye());
            Vector3f ndc = matrix.transformProject(new Vector3f((float) corner.x, (float) corner.y, (float) corner.z));
            assertEquals(-x, ndc.x, 1e-5, "texture U is reversed when drawn on the physical plane");
            assertEquals(y, ndc.y, 1e-5);
        }
    }

    @Test
    void rayMustHitTheFrontWithinMirrorBoundsAndReach() {
        assertEquals(2, mirror.hitDistance(new Vec3(0, 1.1, 0), new Vec3(0, 0, -1), 6), 1e-6);
        assertEquals(-1, mirror.hitDistance(new Vec3(1, 1.1, 0), new Vec3(0, 0, -1), 6));
        assertEquals(-1, mirror.hitDistance(new Vec3(0, 1.1, -3), new Vec3(0, 0, 1), 6));
        assertEquals(-1, mirror.hitDistance(new Vec3(0, 1.1, 0), new Vec3(1, 0, 0), 6));
        assertEquals(-1, mirror.hitDistance(new Vec3(0, 1.1, 0), new Vec3(0, 0, -1), 1));
    }

    @Test
    void grabPreservesPositionThenFollowsControllerTranslation() {
        Vec3 hand = new Vec3(.2, 1.3, -.4);
        Quaternionf rotation = new Quaternionf().rotationY(.4f);
        var grab = VrMirrorGrab.begin(1, mirror, hand, rotation);
        assertEquals(1, grab.hand());
        assertTrue(grab.move(hand, rotation).center().distanceTo(mirror.center()) < 1e-6);
        Vec3 shift = new Vec3(.7, .3, -.2);
        assertTrue(grab.move(hand.add(shift), rotation).center().distanceTo(mirror.center().add(shift)) < 1e-6);
    }

    @Test
    void grabRotationKeepsMirrorRigidAndBackSideDoesNotRender() {
        var grab = VrMirrorGrab.begin(0, mirror, Vec3.ZERO, new Quaternionf());
        Quaternionf turn = new Quaternionf().rotationY((float)Math.PI/2);
        var moved = grab.move(Vec3.ZERO, turn);
        assertTrue(moved.center().distanceTo(VrMirrorGeometry.rotate(mirror.center(), turn)) < 1e-6);
        assertTrue(moved.normal().distanceTo(new Vec3(1, 0, 0)) < 1e-6);
        assertNull(mirror.reflectedView(new Vec3(0, 1, -3)));
    }
}
