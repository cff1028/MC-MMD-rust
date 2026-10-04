package com.shiroha.mmdskin.compat.vr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VrBodyYawStateTest {
    @Test void controllerMotionCannotSteerTheHeadReferencedBody() {
        VrBodyYawState state = new VrBodyYawState();
        state.update(0, 0, rad(15), 0, 0);
        // A single, crossed, rear or lost controller is deliberately not an estimator input.
        // A stationary HMD must stay stationary for all subsequent frames, without drift.
        for (int frame = 1; frame < 120; frame++)
            assertEquals(rad(15), state.update(frame, frame * 16_666_667L, rad(15), 0, 0), 1.0e-6);
        assertEquals(0, state.turnRateRadians());
    }

    @Test void smallHeadLookKeepsTorsoAndLargeLookFollowsWithBoundedSpeed() {
        VrBodyYawState state = new VrBodyYawState();
        state.update(0, 0, 0, 0, 0);
        assertEquals(0, state.update(1, 50_000_000, rad(30), 0, 0));
        float followed = state.update(2, 100_000_000, rad(90), 0, 0);
        assertEquals(rad(7.5f), followed, 1.0e-5);
        assertEquals(rad(150), state.turnRateRadians(), 1.0e-5);
        for (int frame = 3; frame < 30; frame++) state.update(frame, frame * 50_000_000L, rad(90), 0, 0);
        assertEquals(rad(78), state.yawRadians(), 1.0e-5);
        assertEquals(0, state.turnRateRadians(), 1.0e-5);
    }

    @Test void hmdWrapAcross180UsesShortestRotationWithoutAFlip() {
        VrBodyYawState state = new VrBodyYawState();
        state.update(0, 0, rad(179), 0, 0);
        assertEquals(rad(179), state.update(1, 50_000_000, rad(-179), 0, 0), 1.0e-5);
        float yaw = state.update(2, 100_000_000, rad(-130), 0, 0);
        assertEquals(rad(7.5f), VrBodyYawState.wrap(yaw - rad(179)), 1.0e-5);
        assertTrue(state.turnRateRadians() > 0);
    }

    @Test void roomSnapTurnAppliesImmediatelyAndIsNotPhysicalTorsoVelocity() {
        VrBodyYawState state = new VrBodyYawState();
        state.update(0, 0, rad(20), 0, 0);
        assertEquals(rad(-70), state.update(1, 11_111_111, rad(-70), rad(90), 0), 1.0e-5);
        assertEquals(0, state.turnRateRadians());
        assertEquals(rad(20), state.update(2, 22_222_222, rad(20), 0, 0), 1.0e-5);
        assertEquals(0, state.turnRateRadians());
    }

    @Test void roomRotationWrapIsOnlyTwoDegrees() {
        VrBodyYawState state = new VrBodyYawState();
        state.update(0, 0, 0, rad(179), 0);
        assertEquals(rad(-2), state.update(1, 10_000_000, rad(-2), rad(-179), 0), 1.0e-5);
        assertEquals(0, state.turnRateRadians());
    }

    @Test void eyesAndMirrorsInSameFrameCannotAdvanceOrOverwriteTheEstimate() {
        VrBodyYawState state = new VrBodyYawState();
        state.update(10, 0, 0, 0, 0);
        float firstEye = state.update(11, 16_666_667, rad(90), 0, 0);
        float rate = state.turnRateRadians();
        assertEquals(firstEye, state.update(11, 22_000_000, rad(90), 0, 0));
        assertEquals(firstEye, state.update(11, 28_000_000, rad(-90), rad(45), 1));
        assertEquals(rate, state.turnRateRadians());
        float nextFrame = state.update(12, 33_333_334, rad(90), 0, 0);
        assertEquals(firstEye * 2, nextFrame, 1.0e-5);
    }

    @Test void followSpeedDependsOnTimeRatherThanFrameRate() {
        assertEquals(runTurn(30), runTurn(90), 1.0e-5);
    }

    @Test void movementAllowsBodyToFollowModerateLookWithoutChangingCameraHeading() {
        VrBodyYawState idle = new VrBodyYawState();
        VrBodyYawState walking = new VrBodyYawState();
        idle.update(0, 0, 0, 0, 0);
        walking.update(0, 0, 0, 0, 0);
        assertEquals(0, idle.update(1, 50_000_000, rad(25), 0, 0));
        assertEquals(rad(12), walking.update(1, 50_000_000, rad(25), 0, 0.1), 1.0e-5);
    }

    @Test void verticalTrackingAndLongPauseCannotProduceSpikes() {
        VrBodyYawState state = new VrBodyYawState();
        assertTrue(Float.isNaN(VrBodyYawState.headYaw(0.01f, 0.01f)));
        assertTrue(Float.isNaN(VrBodyYawState.headYaw(Float.NaN, 1)));
        state.update(0, 0, 0, 0, 0);
        assertEquals(0, state.update(1, 10_000_000, Float.NaN, 0, 0));
        assertEquals(rad(15), state.update(2, 5_000_000_000L, rad(150), 0, 0), 1.0e-5);
        state.reset();
        assertEquals(rad(-60), state.update(2, 5_000_000_000L, rad(-60), 0, 0), 1.0e-5);
    }

    private static float runTurn(int fps) {
        VrBodyYawState state = new VrBodyYawState();
        state.update(0, 0, 0, 0, 0);
        for (int frame = 1; frame <= fps; frame++)
            state.update(frame, Math.round(frame * 1.0e9 / fps), rad(170), 0, 0);
        return state.yawRadians();
    }

    private static float rad(float degrees) { return (float) Math.toRadians(degrees); }
}
