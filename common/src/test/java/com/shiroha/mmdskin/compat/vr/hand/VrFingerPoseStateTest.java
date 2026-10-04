package com.shiroha.mmdskin.compat.vr.hand;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class VrFingerPoseStateTest {
    private static final long START = 1_000_000_000L;

    @Test void secondEyeCannotAdvanceTheFilterOrRefreshTheTrackingTimeout() {
        var state = new VrFingerPoseState();
        assertTrue(state.update(1, START, START, target(1)));
        float[] first = angles(state);
        assertTrue(state.update(1, START + 150_000_000L, START + 150_000_000L, target(2)));
        assertArrayEquals(first, angles(state));
        assertFalse(state.update(2, START + 230_000_000L, 0, null));
        assertArrayEquals(new float[20], angles(state));
    }

    @Test void equalElapsedTimeProducesEquivalentSmoothingAtDifferentFrameRates() {
        var fast = new VrFingerPoseState();
        var slow = new VrFingerPoseState();
        fast.update(0, START, START, target(0));
        slow.update(0, START, START, target(0));
        for (int i = 1; i <= 18; i++) {
            long now = START + i * 10_000_000L;
            fast.update(i, now, now, target(1));
        }
        for (int i = 1; i <= 6; i++) {
            long now = START + i * 30_000_000L;
            slow.update(i, now, now, target(1));
        }
        assertArrayEquals(angles(fast), angles(slow), 1e-6f);
        assertTrue(angles(fast)[0] > 0.98f && angles(fast)[0] < 1);
    }

    @Test void lossOfTrackingDecaysThenReleasesAndReacquisitionStartsFromReleasedPose() {
        var state = new VrFingerPoseState();
        state.update(1, START, START, target(1));
        float before = angles(state)[0];
        assertTrue(state.update(2, START + 50_000_000L, 0, null));
        assertTrue(angles(state)[0] > 0 && angles(state)[0] < before);
        assertFalse(state.update(3, START + 220_000_000L, 0, null));
        assertArrayEquals(new float[20], angles(state));
        assertTrue(state.update(4, START + 231_000_000L, START + 231_000_000L, target(1)));
        assertTrue(angles(state)[0] > 0 && angles(state)[0] < 0.5f);
    }

    @Test void staleFutureMalformedAndNonFiniteSamplesNeverActivateAnInactiveHand() {
        var state = new VrFingerPoseState();
        assertFalse(state.update(1, START, START - 250_000_001L, target(1)));
        assertFalse(state.update(2, START, START + 1, target(1)));
        assertFalse(state.update(3, START, START, new float[19]));
        float[] nan = target(1); nan[6] = Float.NaN;
        assertFalse(state.update(4, START, START, nan));
        assertArrayEquals(new float[20], angles(state));
        assertTrue(state.update(5, START + 10_000_000L, START - 240_000_000L, target(1)),
                "A sample exactly 250 ms old is still within the accepted boundary");
    }

    @Test void clearingAllowsTheSameFrameNumberToBeginAFreshSession() {
        var state = new VrFingerPoseState();
        state.update(42, START, START, target(1));
        state.clear();
        assertArrayEquals(new float[20], angles(state));
        assertFalse(state.update(42, START + 10_000_000L, 0, null));
        assertTrue(state.update(43, START + 20_000_000L, START + 20_000_000L, target(0.6f)));
    }

    @Test void copyToTouchesOnlyTheSelectedHandsTwentyAngles() {
        var left = new VrFingerPoseState();
        var right = new VrFingerPoseState();
        left.update(1, START, START, target(0.3f));
        right.update(1, START, START, target(0.8f));
        float[] both = new float[40];
        left.copyTo(both, 0); right.copyTo(both, 20);
        assertArrayEquals(angles(left), Arrays.copyOfRange(both, 0, 20));
        assertArrayEquals(angles(right), Arrays.copyOfRange(both, 20, 40));
        assertTrue(both[20] > both[0]);
    }

    private static float[] target(float value) { float[] result = new float[20]; Arrays.fill(result, value); return result; }
    private static float[] angles(VrFingerPoseState state) { float[] result = new float[20]; state.copyTo(result, 0); return result; }
}
