package com.shiroha.mmdskin.compat.vr.hand;

import com.shiroha.mmdskin.bridge.runtime.NativeModelPort;
import com.shiroha.mmdskin.config.ModelConfigData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class VrFingerDriverTest {
    private static final long START = 1_000_000_000L;
    private static final float BLEND = (float) -Math.expm1(-(1f / 90f) / 0.035);

    @Test void oldConfigurationPreservesAnatomicalAnglesAndDeduplicatesBothEyes() {
        Capture capture = new Capture();
        VrFingerDriver driver = new VrFingerDriver();
        var hand = hand(START);
        driver.updateForInputs(capture.port, 1, 5, null, hand, hand, START);
        float[] expected = SteamVrFingerRetargeter.retarget(null, null, true, hand.curls(), hand.splays());
        for (int i = 0; i < 20; i++) assertEquals(expected[i] * BLEND, capture.angles[i], 1e-6);
        assertArrayEquals(Arrays.copyOfRange(capture.angles, 0, 20), Arrays.copyOfRange(capture.angles, 20, 40));
        assertEquals(3, capture.mask);
        assertArrayEquals(new float[2], capture.axes);
        driver.updateForInputs(capture.port, 1, 5, new ModelConfigData(), null, null, START + 50_000_000L);
        assertEquals(1, capture.writes);
    }

    @Test void explicitOffClearsBothOverridesInTheSameFrameAndReenableStartsFresh() {
        Capture capture = new Capture();
        VrFingerDriver driver = new VrFingerDriver();
        ModelConfigData config = new ModelConfigData();
        driver.updateForInputs(capture.port, 1, 10, config, hand(START), hand(START), START);
        float[] initial = capture.angles.clone();
        config.vrFingerTrackingEnabled = false;
        driver.updateForInputs(capture.port, 1, 10, config, hand(START), hand(START), START);
        assertEquals(0, capture.mask);
        assertArrayEquals(new float[40], capture.angles);
        config.vrFingerTrackingEnabled = true;
        driver.updateForInputs(capture.port, 1, 10, config, hand(START), hand(START), START);
        assertEquals(3, capture.mask);
        assertArrayEquals(initial, capture.angles);
        assertEquals(3, capture.writes);
    }

    @Test void perHandOffLeavesTheOtherHandAndHeadArmTrackingUntouched() {
        Capture capture = new Capture();
        VrFingerDriver driver = new VrFingerDriver();
        ModelConfigData config = new ModelConfigData();
        driver.updateForInputs(capture.port, 1, 10, config, hand(START), hand(START), START);
        float[] right = Arrays.copyOfRange(capture.angles, 20, 40);
        config.vrLeftHandTrackingEnabled = false;
        driver.updateForInputs(capture.port, 1, 10, config, hand(START), hand(START), START);
        assertEquals(2, capture.mask);
        assertArrayEquals(new float[20], Arrays.copyOfRange(capture.angles, 0, 20));
        assertArrayEquals(right, Arrays.copyOfRange(capture.angles, 20, 40));
        config.vrRightHandTrackingEnabled = false;
        config.vrLeftHandTrackingEnabled = true;
        driver.updateForInputs(capture.port, 1, 10, config, hand(START), hand(START), START);
        assertEquals(1, capture.mask);
        assertArrayEquals(new float[20], Arrays.copyOfRange(capture.angles, 20, 40));
    }

    @Test void leftThumbCalibrationChangesOnlyThumbAndPositiveOppositionMovesInward() {
        Capture baseline = new Capture();
        new VrFingerDriver().updateForInputs(baseline.port, 1, 1, null, hand(START), hand(START), START);
        ModelConfigData config = new ModelConfigData();
        config.vrLeftThumb.baseCurlScale = 1.5f;
        config.vrLeftThumb.middleCurlScale = 2;
        config.vrLeftThumb.tipCurlScale = 0.5f;
        config.vrLeftThumb.baseCurlOffsetDeg = 10;
        config.vrLeftThumb.oppositionOffsetDeg = 20;
        config.vrLeftThumb.curlAxisOffsetDeg = 90;
        config.vrRightThumb.curlAxisOffsetDeg = -45;
        Capture calibrated = new Capture();
        new VrFingerDriver().updateForInputs(calibrated.port, 1, 1, config, hand(START), hand(START), START);
        assertEquals(baseline.angles[0] * 1.5f + Math.toRadians(10) * BLEND, calibrated.angles[0], 1e-6);
        assertEquals(baseline.angles[1] * 2, calibrated.angles[1], 1e-6);
        assertEquals(baseline.angles[2] * .5f, calibrated.angles[2], 1e-6);
        assertEquals(baseline.angles[15] - Math.toRadians(20) * BLEND, calibrated.angles[15], 1e-6);
        for (int i = 3; i < 40; i++) {
            if (i != 15) assertEquals(baseline.angles[i], calibrated.angles[i], "Untouched angle " + i);
        }
        assertEquals(Math.PI / 2, calibrated.axes[0], 1e-6);
        assertEquals(-Math.PI / 4, calibrated.axes[1], 1e-6);
    }

    @Test void liveEditsAreDetectedByValueEvenWhenTheSameConfigObjectIsReused() {
        Capture capture = new Capture();
        VrFingerDriver driver = new VrFingerDriver();
        ModelConfigData config = new ModelConfigData();
        driver.updateForInputs(capture.port, 1, 4, config, hand(START), hand(START), START);
        config.vrLeftThumb.curlAxisOffsetDeg = -90;
        config.vrRightThumb.baseCurlScale = 0;
        driver.updateForInputs(capture.port, 1, 4, config, hand(START), hand(START), START + 10_000_000L);
        assertEquals(2, capture.writes);
        assertEquals(-Math.PI / 2, capture.axes[0], 1e-6);
        assertTrue(capture.angles[20] < capture.angles[0]);
    }

    @Test void calibrationOffsetsFadeOnTrackingLossAndDoNotActivateAbsentHands() {
        Capture capture = new Capture();
        VrFingerDriver driver = new VrFingerDriver();
        ModelConfigData config = new ModelConfigData();
        config.vrLeftThumb.baseCurlOffsetDeg = 80;
        config.vrLeftThumb.oppositionOffsetDeg = 80;
        driver.updateForInputs(capture.port, 1, 1, config, null, null, START);
        assertEquals(0, capture.mask);
        driver.updateForInputs(capture.port, 1, 2, config, hand(START), null, START + 10_000_000L);
        assertEquals(1, capture.mask);
        float before = capture.angles[0];
        driver.updateForInputs(capture.port, 1, 3, config, null, null, START + 60_000_000L);
        assertTrue(capture.angles[0] < before);
        driver.updateForInputs(capture.port, 1, 4, config, null, null, START + 240_000_000L);
        assertEquals(0, capture.mask);
        assertArrayEquals(new float[40], capture.angles);
    }

    @Test void malformedSettingsAreSanitizedWithoutMutatingTheLiveConfig() {
        Capture capture = new Capture();
        ModelConfigData config = new ModelConfigData();
        config.vrLeftThumb = null;
        config.vrRightThumb.baseCurlScale = Float.NaN;
        config.vrRightThumb.middleCurlScale = 100;
        config.vrRightThumb.tipCurlScale = -2;
        config.vrRightThumb.curlAxisOffsetDeg = 1000;
        config.vrRightThumb.oppositionOffsetDeg = Float.POSITIVE_INFINITY;
        new VrFingerDriver().updateForInputs(capture.port, 1, 1, config, hand(START), hand(START), START);
        assertEquals(capture.angles[0], capture.angles[20]);
        assertEquals(0, capture.angles[22]);
        assertEquals(Math.PI, capture.axes[1], 1e-6);
        for (float value : capture.angles) assertTrue(Float.isFinite(value));
        assertNull(config.vrLeftThumb);
        assertTrue(Float.isNaN(config.vrRightThumb.baseCurlScale));
    }

    @Test void newModelHandleCannotInheritStaleFingerPoseEvenWithinTheSameFrame() {
        Capture capture = new Capture();
        VrFingerDriver driver = new VrFingerDriver();
        driver.updateForInputs(capture.port, 1, 1, null, hand(START), hand(START), START);
        driver.updateForInputs(capture.port, 2, 1, null, null, null, START);
        assertEquals(0, capture.mask);
        assertEquals(2, capture.writes);
        assertArrayEquals(new float[40], capture.angles);
    }

    private static SteamVrHandProvider.RawHand hand(long now) {
        return new SteamVrHandProvider.RawHand(1, now, 3, 2, true, 2,
                null, null, new float[]{.4f, .2f, .6f, .8f, 1}, new float[]{1, .5f, .5f, .5f});
    }

    private static class Capture {
        float[] angles, axes;
        int mask, writes;
        final NativeModelPort port = (NativeModelPort) Proxy.newProxyInstance(
                NativeModelPort.class.getClassLoader(), new Class<?>[]{NativeModelPort.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setVrFingerTracking" -> {
                            angles = ((float[]) args[1]).clone();
                            mask = (int) args[2];
                            writes++;
                        }
                        case "setVrThumbCalibration" -> axes = new float[]{(float) args[1], (float) args[2]};
                        default -> fail("Unexpected native operation: " + method.getName());
                    }
                    return null;
                });
    }
}
