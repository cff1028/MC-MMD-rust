package com.shiroha.mmdskin.compat.vr;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VrLocomotionStateTest {
    private static final float SCALE = .1f;

    @Test void followsActualSpeedAndBodyRelativeDirectionAtDifferentRefreshRates() {
        var sixty = straightRun(60, 4, 0);
        var ninety = straightRun(90, 4, 0);
        assertEquals(sixty.velocityZ(), ninety.velocityZ(), .05f);
        assertEquals(39.1f, sixty.velocityZ(), .1f);
        var backward = straightRun(90, -2, 0);
        assertTrue(backward.velocityZ() < 0);
        var sideways = straightRun(90, 4, (float) (Math.PI / 2));
        assertEquals(sixty.velocityZ(), sideways.velocityX(), .05f);
        assertEquals(0, sideways.velocityZ(), .001f);
    }

    @Test void samplesBothEyesOnlyOnceAndLeavesTurnDirectionInModelSpace() {
        var state = new VrLocomotionState();
        state.sample(1, 1_000_000_000L, 0, 0, 0, 0, SCALE, true, false);
        var first = state.sample(2, 1_020_000_000L, 0, .08, 0, 1, SCALE, true, false);
        var second = state.sample(2, 1_029_000_000L, 20, 20, 3, 9, SCALE, false, true);
        assertSame(first, second);
        assertEquals(-1, first.turnRate());
    }

    @Test void ignoresHeadsetJitterAndSettlesAfterStopping() {
        var state = new VrLocomotionState();
        VrLocomotionState.Motion value = null;
        for (int i = 0; i < 180; i++) value = state.sample(i, i * 10_000_000L, (i % 2) * .0001, 0,
                0, 0, SCALE, true, false);
        assertEquals(0, value.velocityX());
        for (int i = 180; i < 280; i++) state.sample(i, i * 10_000_000L, 0, (i - 180) * .04,
                0, 0, SCALE, true, false);
        for (int i = 280; i < 450; i++) value = state.sample(i, i * 10_000_000L, 0, 3.96,
                0, 0, SCALE, true, false);
        assertEquals(0, value.velocityZ());
    }

    @Test void suppressesTeleportsTrackingGapsAndNonWalkingStates() {
        var state = new VrLocomotionState();
        state.sample(1, 1_000_000_000L, 0, 0, 0, 0, SCALE, true, false);
        assertFalse(state.sample(2, 1_010_000_000L, 100, 100, 0, 4, SCALE, true, false).allowed());
        assertFalse(state.sample(3, 1_020_000_000L, 100, 100.05, 0, 4, SCALE, false, false).allowed());
        var landing = state.sample(4, 1_030_000_000L, 100, 101, 0, 0, SCALE, true, true);
        assertEquals(0, landing.velocityZ());
        assertTrue(landing.crouching());
        assertFalse(state.sample(5, 4_000_000_000L, 105, 105, 0, 0, SCALE, true, false).allowed());
        assertFalse(state.sample(6, 4_010_000_000L, Double.NaN, 105, 0, 0, SCALE, true, false).allowed());
    }

    private static VrLocomotionState.Motion straightRun(int hz, float speed, float yaw) {
        var state = new VrLocomotionState();
        VrLocomotionState.Motion result = null;
        for (int i = 0; i <= hz * 2; i++) result = state.sample(i, (long) (i * 1.0e9 / hz), 0,
                i * (double) speed / hz, yaw, 0, SCALE, true, false);
        return result;
    }
}
