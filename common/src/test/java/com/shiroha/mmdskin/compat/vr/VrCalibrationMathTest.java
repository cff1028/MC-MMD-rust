package com.shiroha.mmdskin.compat.vr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VrCalibrationMathTest {
    private static final float EPSILON = 0.0001f;

    private VrCalibrationMath.ModelMetrics metrics() {
        return VrCalibrationMath.ModelMetrics.fromNative(new float[]{18, 6, 6, 2, 15.5f, 0, -2, 15.5f, 0}, 0.1f);
    }

    private float[] tPose(float height, float span, float floorY) {
        return new float[]{0, floorY + height, 0, 0, 0, 0, 1,
                span / 2, floorY + height - 0.25f, 0, 0, 0, 0, 1,
                -span / 2, floorY + height - 0.25f, 0, 0, 0, 0, 1};
    }

    @Test
    void shouldSolveBodyHeightAndReachMappingSeparatelyWithShoulderWidthRemoved() {
        // Uniform body scale 1.2; physical reach is 1.1 times the fixed avatar arm length.
        float span = (4 + 12 * 1.1f) * 0.12f;
        var measured = VrCalibrationMath.measure(tPose(2.16f, span, 64), 64);
        var result = VrCalibrationMath.solve(metrics(), measured);
        assertTrue(result.usable());
        assertEquals(1.2f, result.modelScale(), EPSILON);
        assertEquals(1.1f, result.armLengthScale(), EPSILON);
    }

    @Test
    void shouldCalibrateHumanReachOnShortArmedAvatarWithoutChangingItsDimensions() {
        var model = VrCalibrationMath.ModelMetrics.fromNative(
                new float[]{19.47527f, 5.501173f, 5.5011697f, 1.2780769f, 17.290936f, -.55546105f,
                        -1.278079f, 17.290936f, -.5554574f}, .09f);
        var measurement = VrCalibrationMath.measure(tPose(1.592f, 1.62f, 0), 0);
        var result = VrCalibrationMath.solve(model, measurement);
        assertTrue(result.usable());
        assertTrue(result.armLengthScale() > 1.5f, "A valid human T-pose must not be rejected by the old bone-stretch limit");
        float avatarArm = model.meanArmLengthMetres(result.modelScale());
        float userArm = avatarArm * result.armLengthScale();
        assertEquals((1.62f - model.shoulderWidth() * model.baseWorldScale() * result.modelScale()) / 2,
                userArm, EPSILON);
        assertEquals(5.501173f, model.leftArmLength(), EPSILON);
    }

    @Test
    void shouldUseGroundRelativeHmdHeightAndRawRestDimensions() {
        var result = VrCalibrationMath.solve(metrics(), VrCalibrationMath.measure(tPose(1.8f, 1.6f, -20), -20));
        assertTrue(result.usable());
        assertEquals(1, result.modelScale(), EPSILON);
        assertEquals(1, result.armLengthScale(), EPSILON);
    }

    @Test
    void shouldRejectMissingOrNonFiniteHeadOrEitherController() {
        float[] packet = tPose(1.8f, 1.6f, 0);
        packet[20] = 0; // A missing left hand has no valid quaternion.
        assertEquals(VrCalibrationMath.Problem.TRACKING, VrCalibrationMath.measure(packet, 0).problem());
        packet = tPose(1.8f, 1.6f, 0);
        packet[7] = Float.NaN;
        assertEquals(VrCalibrationMath.Problem.TRACKING, VrCalibrationMath.measure(packet, 0).problem());
    }

    @Test
    void shouldRejectLowHandsAndHandsPointingForwardInsteadOfATPose() {
        float[] packet = tPose(1.8f, 1.6f, 0);
        packet[8] = packet[15] = 0.7f;
        assertEquals(VrCalibrationMath.Problem.T_POSE, VrCalibrationMath.measure(packet, 0).problem());
        packet = tPose(1.8f, 1.6f, 0);
        packet[9] = packet[16] = -0.6f;
        assertEquals(VrCalibrationMath.Problem.T_POSE, VrCalibrationMath.measure(packet, 0).problem());
        packet = tPose(1.8f, 1.6f, 0);
        packet[7] = 0; packet[9] = 0.8f; packet[14] = 0; packet[16] = -0.8f;
        assertEquals(VrCalibrationMath.Problem.T_POSE, VrCalibrationMath.measure(packet, 0).problem());
    }

    @Test
    void shouldNotCalibrateSeatedHeightOrSilentlyClampUnsupportedProportions() {
        assertEquals(VrCalibrationMath.Problem.STANDING, VrCalibrationMath.measure(tPose(0.6f, 1.6f, 0), 0).problem());
        var result = VrCalibrationMath.solve(metrics(), VrCalibrationMath.measure(tPose(0.8f, 1.6f, 0), 0));
        assertEquals(VrCalibrationMath.Problem.RANGE, result.problem());
    }

    @Test
    void shouldRejectModelsWithoutTwoUsableRestArmChains() {
        assertNull(VrCalibrationMath.ModelMetrics.fromNative(new float[]{18, 6, 0, 2, 15, 0, -2, 15, 0}, 0.1f));
        assertNull(VrCalibrationMath.ModelMetrics.fromNative(new float[]{Float.NaN, 6, 6, 2, 15, 0, -2, 15, 0}, 0.1f));
        assertNull(VrCalibrationMath.ModelMetrics.fromNative(new float[]{18, 6, 6}, 0.1f));
    }

    @Test
    void shouldRequireStablePoseWindowAndResetAfterMovementOrTrackingLoss() {
        var window = new VrCalibrationMath.StabilityWindow();
        var stable = VrCalibrationMath.measure(tPose(1.8f, 1.6f, 0), 0);
        for (int i = 0; i < 7; i++) window.add(stable);
        assertFalse(window.ready());
        window.add(stable);
        assertTrue(window.ready());
        assertEquals(1.8f, window.average().eyeHeightMetres(), EPSILON);
        window.add(VrCalibrationMath.measure(tPose(1.8f, 1.75f, 0), 0));
        assertFalse(window.ready());
        for (int i = 0; i < 8; i++) window.add(stable);
        assertTrue(window.ready());
        window.add(VrCalibrationMath.measure(null, 0));
        assertFalse(window.ready());
    }

    @Test
    void shouldBeInvariantToWorldYawAndTranslation() {
        float[] packet = tPose(1.8f, 1.6f, 0);
        float sinHalf = (float) Math.sin(Math.PI / 4), cosHalf = (float) Math.cos(Math.PI / 4);
        for (int i = 0; i < 21; i += 7) {
            float x = packet[i], z = packet[i + 2];
            packet[i] = z + 120;
            packet[i + 2] = -x - 50;
            packet[i + 4] = sinHalf;
            packet[i + 6] = cosHalf;
        }
        var measured = VrCalibrationMath.measure(packet, 0);
        assertTrue(measured.usable());
        var result = VrCalibrationMath.solve(metrics(), measured);
        assertEquals(1, result.modelScale(), EPSILON);
        assertEquals(1, result.armLengthScale(), EPSILON);
    }
}
