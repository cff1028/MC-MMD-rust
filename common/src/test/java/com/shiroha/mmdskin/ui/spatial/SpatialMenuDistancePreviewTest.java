package com.shiroha.mmdskin.ui.spatial;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.shiroha.mmdskin.ui.spatial.SpatialMenuTransforms.*;

class SpatialMenuDistancePreviewTest {
    private static final WorldFrame ROOM = new WorldFrame(0, 0, 0, 0, 1);
    private static final Vector3f HEAD = new Vector3f(0, 1.6f, 0), FORWARD = new Vector3f(0, 0, -1);

    @Test void previewLeavesAllFourInteractivePanelPosesUntouched() {
        for (DetailBehavior behavior : DetailBehavior.values()) {
            var actual = new DetailMotion();
            assertTrue(actual.update(ROOM, HEAD, FORWARD, behavior, 1.35, 4, 180, 180, 0));
            var original = actual.roomPose(ROOM);
            var preview = new DetailMotion();
            for (double distance : new double[]{.6, 2.5, 1.35, 1.8}) {
                var movedHead = new Vector3f(.2f, 1.7f, .1f);
                assertTrue(preview.previewDistanceFrom(actual, ROOM, movedHead, new Vector3f(.3f, .1f, -1), distance));
                assertTrue(original.equals(actual.roomPose(ROOM), 0), behavior.toString());
                assertEquals(distance, preview.roomPose(ROOM).getTranslation(new Vector3f()).distance(movedHead), .00001);
                assertEquals(0, preview.roomPose(ROOM).transformDirection(1, 0, 0, new Vector3f()).y, .00001);
            }
            preview.reset();
            assertNull(preview.roomPose(ROOM));
            assertTrue(original.equals(actual.roomPose(ROOM), 0), "Cancelling must not change actual pose");
        }
    }

    @Test void releaseKeepsCurrentPoseThenEasesToTheExactPreview() {
        for (DetailBehavior behavior : DetailBehavior.values()) {
            var actual = new DetailMotion(); var preview = new DetailMotion();
            actual.update(ROOM, HEAD, FORWARD, behavior, 1.35, 4, 180, 180, 0);
            var original = actual.roomPose(ROOM);
            preview.previewDistanceFrom(actual, ROOM, HEAD, FORWARD, 2.2);
            var expected = preview.roomPose(ROOM);
            actual.acceptDistancePreview(preview);
            assertTrue(original.equals(actual.roomPose(ROOM), 0), "Commit must not snap the panel");
            actual.update(ROOM, HEAD, FORWARD, behavior, 2.2, 7, 180, 180, .016);
            var step = actual.roomPose(ROOM).getTranslation(new Vector3f());
            assertTrue(step.distance(expected.getTranslation(new Vector3f())) < original.getTranslation(new Vector3f()).distance(expected.getTranslation(new Vector3f())));
            assertFalse(actual.roomPose(ROOM).equals(expected, .001f));
            actual.update(ROOM, HEAD, FORWARD, behavior, 2.2, 7, 0, 0, .016);
            assertTrue(expected.equals(actual.roomPose(ROOM), .00001f), behavior.toString());
        }
    }

    @Test void previewAndCommitStayAccurateAtWorldBorderWithRoomScaleAndRotation() {
        var world = new WorldFrame(29_990_000.25, 80, -29_990_000.75, 1.2f, 2.5f);
        var actual = new DetailMotion(); var preview = new DetailMotion();
        actual.update(world, HEAD, FORWARD, DetailBehavior.FIXED_STAY, 1.35, 4, 0, 0, 0);
        preview.previewDistanceFrom(actual, world, HEAD, FORWARD, .7);
        var position = world.toWorld(preview.roomPose(world).getTranslation(new Vector3f()));
        assertEquals(.7 * world.scale(), position.distance(world.toWorld(HEAD)), .00001);
        // An eye's interpolated room origin differs from the poll origin.
        var eyeFrame = new WorldFrame(world.x() + .08, world.y(), world.z() - .1, 1.22f, world.scale());
        assertEquals(0, position.distance(eyeFrame.toWorld(preview.roomPose(eyeFrame).getTranslation(new Vector3f()))), .00001);
        actual.acceptDistancePreview(preview);
        actual.update(world, HEAD, FORWARD, DetailBehavior.FIXED_STAY, .7, 4, 0, 0, .016);
        assertTrue(actual.roomPose(eyeFrame).equals(preview.roomPose(eyeFrame), .00001f));
    }

    @Test void invalidPreviewCannotOverwriteLiveDestination() {
        var actual = new DetailMotion(); var preview = new DetailMotion();
        actual.update(ROOM, HEAD, FORWARD, DetailBehavior.FIXED_STAY, 1.35, 4, 0, 0, 0);
        var original = actual.roomPose(ROOM);
        assertFalse(preview.previewDistanceFrom(actual, null, HEAD, FORWARD, 2));
        actual.acceptDistancePreview(preview);
        actual.update(ROOM, HEAD, FORWARD, DetailBehavior.FIXED_STAY, 1.35, 4, 0, 0, .016);
        assertTrue(original.equals(actual.roomPose(ROOM), 0));
    }
}
