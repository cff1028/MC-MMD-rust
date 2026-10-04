package com.shiroha.mmdskin.compat.vr.pointer;

import com.shiroha.mmdskin.config.VrPointerConfigData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VrPointerAnimationTest {
    private static VrPointerAnimation.Settings style(boolean color, boolean radius) {
        return new VrPointerAnimation.Settings(0x000000, 0xFF0000, 0xFFFFFF, 0x0000FF,
                .006f, .012f, color, 100, radius, 100, 100, 200);
    }
    private static long ms(long value) { return value * 1_000_000; }

    @Test void hideAndShowInterruptVisibilityFromTheCurrentValue() {
        var animation = new VrPointerAnimation(); var style = style(true, true);
        assertEquals(0, animation.sample(0, 0, true, false, style).visibility());
        assertEquals(.5f, animation.sample(1, ms(50), true, false, style).visibility());
        assertEquals(.5f, animation.sample(1, ms(90), false, false, style).visibility());
        assertEquals(.25f, animation.sample(2, ms(150), false, false, style).visibility());
        assertEquals(.25f, animation.sample(2, ms(180), true, false, style).visibility());
        assertEquals(.625f, animation.sample(3, ms(200), true, false, style).visibility());
        assertEquals(1, animation.sample(4, ms(250), true, false, style).visibility());
    }

    @Test void pressingAndReleasingRetargetsColorsAndRadiusWithoutJumping() {
        var animation = new VrPointerAnimation(); var style = style(true, true);
        animation.sample(0, 0, true, false, style);
        var start = animation.sample(1, ms(100), true, true, style);
        assertEquals(0x000000, start.rayColor()); assertEquals(.006f, start.dotRadius());
        var half = animation.sample(2, ms(150), true, true, style);
        assertEquals(0x808080, half.rayColor()); assertEquals(0x800080, half.dotColor());
        assertEquals(.009f, half.dotRadius(), 1e-7);
        var release = animation.sample(2, ms(170), true, false, style);
        assertEquals(half, release);
        var returning = animation.sample(3, ms(200), true, false, style);
        assertEquals(0x404040, returning.rayColor()); assertEquals(0xBF0040, returning.dotColor());
        assertEquals(.0075f, returning.dotRadius(), 1e-7);
        var done = animation.sample(4, ms(250), true, false, style);
        assertEquals(0, done.rayColor()); assertEquals(.006f, done.dotRadius(), 1e-7);
    }

    @Test void disablingEitherAnimationSnapsOnlyThatPropertyToItsCurrentPressedTarget() {
        var animation = new VrPointerAnimation();
        animation.sample(0, 0, true, true, style(true, true));
        var half = animation.sample(1, ms(50), true, true, style(true, true));
        var noColor = animation.sample(1, ms(80), true, true, style(false, true));
        assertEquals(0xFFFFFF, noColor.rayColor()); assertEquals(0x0000FF, noColor.dotColor());
        assertEquals(half.dotRadius(), noColor.dotRadius());
        var noRadius = animation.sample(1, ms(90), true, true, style(false, false));
        assertEquals(.012f, noRadius.dotRadius());
        var released = animation.sample(1, ms(95), true, false, style(false, false));
        assertEquals(0x000000, released.rayColor()); assertEquals(.006f, released.dotRadius());
    }

    @Test void sameVrFrameNeverAdvancesForTheSecondEyeOrMirror() {
        var animation = new VrPointerAnimation(); var style = style(true, true);
        animation.sample(44, ms(100), true, true, style);
        var firstEye = animation.sample(45, ms(150), true, true, style);
        assertEquals(firstEye, animation.sample(45, ms(175), true, true, style));
        assertEquals(firstEye, animation.sample(45, ms(199), true, true, style));
        assertEquals(1, animation.sample(46, ms(200), true, true, style).visibility());
    }

    @Test void liveTargetAndDurationChangesRestartFromThePresentValue() {
        var animation = new VrPointerAnimation(); var old = style(true, true);
        animation.sample(0, 0, true, true, old);
        var current = animation.sample(1, ms(40), true, true, old);
        var edited = new VrPointerAnimation.Settings(0, 0xFF0000, 0x00FF00, 0x00FF00,
                .006f, .020f, true, 200, true, 200, 100, 200);
        assertEquals(current, animation.sample(1, ms(80), true, true, edited));
        var later = animation.sample(2, ms(140), true, true, edited);
        assertEquals(0x33B333, later.rayColor());
        assertEquals((current.dotRadius() + .02f) / 2, later.dotRadius(), 1e-7);
    }

    @Test void zeroDurationSnapsAndLongOrRegressingTimesStayFinite() {
        var animation = new VrPointerAnimation();
        var instant = new VrPointerAnimation.Settings(0, 0, 0xFFFFFF, 0xFFFFFF,
                .006f, .012f, true, 0, true, 0, 0, 0);
        var sample = animation.sample(0, 0, true, true, instant);
        assertEquals(1, sample.visibility()); assertEquals(0xFFFFFF, sample.rayColor()); assertEquals(.012f, sample.dotRadius());
        assertEquals(0, animation.sample(0, ms(100), false, true, instant).visibility());
        animation.reset();
        animation.sample(0, ms(100), true, true, style(true, true));
        var regressed = animation.sample(1, ms(50), true, true, style(true, true));
        assertEquals(0, regressed.visibility());
        var completed = animation.sample(2, Long.MAX_VALUE, true, true, style(true, true));
        assertEquals(1, completed.visibility()); assertEquals(0xFFFFFF, completed.rayColor()); assertEquals(.012f, completed.dotRadius());
    }

    @Test void invalidSettingsAndResetCannotLeakNonfiniteOrOldHandValues() {
        var animation = new VrPointerAnimation();
        var invalid = new VrPointerAnimation.Settings(-1, -1, -1, -1,
                Float.NaN, Float.POSITIVE_INFINITY, true, Float.NaN, true, Float.POSITIVE_INFINITY, Float.NaN, -1);
        var result = animation.sample(0, 0, true, true, invalid);
        assertTrue(Float.isFinite(result.dotRadius())); assertTrue(Float.isFinite(result.visibility()));
        animation.sample(1, ms(1000), true, true, invalid);
        animation.reset();
        var fresh = animation.sample(1, ms(1000), false, false, style(true, true));
        assertEquals(0, fresh.visibility()); assertEquals(0, fresh.rayColor()); assertEquals(.006f, fresh.dotRadius());
    }

    @Test void twoHandsKeepIndependentTransitionsAndConfigSnapshots() {
        var left = new VrPointerAnimation(); var right = new VrPointerAnimation();
        var config = new VrPointerConfigData();
        config.dotRadiusView = Float.NaN;
        var captured = VrPointerAnimation.Settings.fromConfig(config);
        assertEquals(.003f, captured.dotRadius()); assertTrue(Float.isNaN(config.dotRadiusView));
        left.sample(0, 0, true, true, style(true, true));
        right.sample(0, 0, false, false, style(true, true));
        assertEquals(.5f, left.sample(1, ms(50), true, true, style(true, true)).visibility());
        assertEquals(0, right.sample(1, ms(50), false, false, style(true, true)).visibility());
    }
}
