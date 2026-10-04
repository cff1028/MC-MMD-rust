package com.shiroha.mmdskin.ui.spatial;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static com.shiroha.mmdskin.ui.spatial.SpatialMenuTransforms.DetailBehavior.*;
import static org.junit.jupiter.api.Assertions.*;

class SpatialMenuMotionTest {
    private static final SpatialMenuTransforms.WorldFrame ROOM = new SpatialMenuTransforms.WorldFrame(0, 0, 0, 0, 1);
    private static final Vector3f HEAD = new Vector3f(0, 1.6f, 0), FORWARD = new Vector3f(0, 0, -1);

    @Test void fixedPanelStaysAtWorldAnchorThroughLocomotionAndSnapTurns() {
        var state = new SpatialMenuTransforms.DetailMotion();
        assertTrue(update(state, ROOM, HEAD, FORWARD, FIXED_STAY, 1.35, 0, 0, 0));
        Vector3d first = worldCenter(state, ROOM);
        var moved = new SpatialMenuTransforms.WorldFrame(7, 0, -3, (float)Math.PI / 2, 1);
        assertTrue(update(state, moved, HEAD, FORWARD, FIXED_STAY, 1.35, 0, 0, .016));
        assertEquals(0, first.distance(worldCenter(state, moved)), .00001);
        Vector3f worldRight = state.roomPose(moved).transformDirection(1, 0, 0, new Vector3f()).rotateY(moved.rotation());
        assertEquals(1, worldRight.x, .00001);
        assertEquals(0, worldRight.y, .00001);
    }

    @Test void onlyFixedCloseModeClosesAfterLeavingThePanel() {
        for (var mode : new SpatialMenuTransforms.DetailBehavior[]{FIXED_CLOSE, FIXED_STAY}) {
            var state = new SpatialMenuTransforms.DetailMotion();
            assertTrue(update(state, ROOM, HEAD, FORWARD, mode, 1.35, 0, 0, 0));
            var teleported = new SpatialMenuTransforms.WorldFrame(12, 0, 0, 0, 1);
            assertEquals(mode == FIXED_STAY, update(state, teleported, HEAD, FORWARD, mode, 1.35, 0, 0, .016));
        }
    }

    @Test void translationOnlyFollowKeepsOpeningDirectionWhileViewFollowTurns() {
        var translation = new SpatialMenuTransforms.DetailMotion();
        var view = new SpatialMenuTransforms.DetailMotion();
        update(translation, ROOM, HEAD, FORWARD, FOLLOW_POSITION, 1.35, 0, 0, 0);
        update(view, ROOM, HEAD, FORWARD, FOLLOW_VIEW, 1.35, 0, 0, 0);
        Vector3f newHead = new Vector3f(.5f, 1.3f, 0), turned = new Vector3f(1, .4f, 0).normalize();
        update(translation, ROOM, newHead, turned, FOLLOW_POSITION, 1.35, 0, 0, .016);
        update(view, ROOM, newHead, turned, FOLLOW_VIEW, 1.35, 0, 0, .016);
        Vector3d translationOffset = worldCenter(translation, ROOM).sub(new Vector3d(newHead));
        Vector3d viewOffset = worldCenter(view, ROOM).sub(new Vector3d(newHead));
        assertEquals(0, translationOffset.x, .00001);
        assertTrue(translationOffset.z < -1.3);
        assertTrue(viewOffset.x > 1.2);
        assertEquals(1.35, viewOffset.length(), .00001);
        assertEquals(0, view.roomPose(ROOM).transformDirection(1, 0, 0, new Vector3f()).y, .00001, "View roll must never tilt the panel");
    }

    @Test void translationAndRotationEasingAreIndependentOfFrameRateAndTakeShortestYaw() {
        var slow = new SpatialMenuTransforms.DetailMotion();
        var fast = new SpatialMenuTransforms.DetailMotion();
        Vector3f start = new Vector3f(-.05f, 0, 1), finish = new Vector3f(.05f, .1f, 1);
        update(slow, ROOM, HEAD, start, FOLLOW_VIEW, 1.35, 180, 180, 0);
        update(fast, ROOM, HEAD, start, FOLLOW_VIEW, 1.35, 180, 180, 0);
        Vector3f moved = new Vector3f(.4f, 1.7f, .2f);
        for (int i = 0; i < 30; i++) update(slow, ROOM, moved, finish, FOLLOW_VIEW, 1.35, 180, 180, 1.0 / 30);
        for (int i = 0; i < 144; i++) update(fast, ROOM, moved, finish, FOLLOW_VIEW, 1.35, 180, 180, 1.0 / 144);
        assertTrue(slow.roomPose(ROOM).equals(fast.roomPose(ROOM), .00002f));
        assertTrue(slow.roomPose(ROOM).transformDirection(0, 0, 1, new Vector3f()).z < -.9, "Yaw seam must not cause a full spin");
        assertEquals(0, fast.roomPose(ROOM).transformDirection(1, 0, 0, new Vector3f()).y, .00001);
    }

    @Test void teleportingFollowingPanelSnapsInsteadOfFlyingThroughTheWorld() {
        var state = new SpatialMenuTransforms.DetailMotion();
        update(state, ROOM, HEAD, FORWARD, FOLLOW_VIEW, 1.35, 2000, 2000, 0);
        var distant = new SpatialMenuTransforms.WorldFrame(500, 80, -200, 1, 1);
        assertTrue(update(state, distant, HEAD, FORWARD, FOLLOW_VIEW, 1.35, 2000, 2000, .016));
        assertEquals(1.35, worldCenter(state, distant).distance(distant.toWorld(HEAD)), .00001);
    }

    @Test void distanceChangeRepositionsCurrentMenuAndCloseThresholdAlwaysExceedsTwiceIt() {
        var state = new SpatialMenuTransforms.DetailMotion();
        update(state, ROOM, HEAD, FORWARD, FIXED_CLOSE, 1.35, 0, 0, 0);
        assertTrue(update(state, ROOM, HEAD, FORWARD, FIXED_CLOSE, 2.5, 0, 0, .016));
        assertEquals(2.5, worldCenter(state, ROOM).distance(ROOM.toWorld(HEAD)), .00001);
        assertTrue(SpatialMenuTransforms.minimumCloseDistance(2.5, 2) > 5);
        assertTrue(SpatialMenuTransforms.minimumCloseDistance(2.5, Double.NaN) > 5);
        assertEquals(8, SpatialMenuTransforms.minimumCloseDistance(2.5, 8));
    }

    @Test void renderInterpolationAndDistantWorldCoordinatesDoNotMoveTheAnchor() {
        var state = new SpatialMenuTransforms.DetailMotion();
        var sample = new SpatialMenuTransforms.WorldFrame(29_999_999.125, 60, -29_999_990.75, .7f, 2);
        update(state, sample, HEAD, FORWARD, FIXED_STAY, 1.35, 0, 0, 0);
        Vector3d anchor = worldCenter(state, sample);
        var render = new SpatialMenuTransforms.WorldFrame(sample.x() + .035, sample.y(), sample.z() - .07, .72f, 2);
        assertEquals(0, anchor.distance(worldCenter(state, render)), .00001);
        assertEquals(2.7, anchor.distance(sample.toWorld(HEAD)), .00001);
    }

    @Test void invalidTrackingClearsMotionAndNewSessionStartsAtNewHead() {
        var state = new SpatialMenuTransforms.DetailMotion();
        update(state, ROOM, HEAD, FORWARD, FIXED_STAY, 1.35, 180, 180, 0);
        assertFalse(update(state, ROOM, new Vector3f(Float.NaN, 0, 0), FORWARD, FIXED_STAY, 1.35, 180, 180, .016));
        assertNull(state.roomPose(ROOM));
        Vector3f moved = new Vector3f(5, 1.6f, -1);
        assertTrue(update(state, ROOM, moved, FORWARD, FIXED_STAY, 1.35, 180, 180, .016));
        assertEquals(1.35, worldCenter(state, ROOM).distance(ROOM.toWorld(moved)), .00001);
    }

    private static boolean update(SpatialMenuTransforms.DetailMotion state, SpatialMenuTransforms.WorldFrame world,
            Vector3f head, Vector3f direction, SpatialMenuTransforms.DetailBehavior mode,
            double distance, double positionMs, double rotationMs, double seconds) {
        return state.update(world, head, direction, mode, distance, 4, positionMs, rotationMs, seconds);
    }
    private static Vector3d worldCenter(SpatialMenuTransforms.DetailMotion state, SpatialMenuTransforms.WorldFrame world) {
        Matrix4f pose = state.roomPose(world);
        return world.toWorld(pose.getTranslation(new Vector3f()));
    }
}
