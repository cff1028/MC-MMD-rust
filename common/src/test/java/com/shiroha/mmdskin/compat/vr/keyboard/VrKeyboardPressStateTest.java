package com.shiroha.mmdskin.compat.vr.keyboard;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VrKeyboardPressStateTest {
    @Test void openingInsideKeyOrWithHeldTriggerNeedsReleaseBeforeFirstPress() {
        var state = new VrKeyboardPressState();
        assertNull(state.update("a", true, false, false, 0));
        state.update("a", false, true, false, 10);
        assertEquals("a", state.update("a", true, false, false, 20));
        assertNull(state.update("a", true, false, false, 30));
    }

    @Test void slidingAcrossKeysCannotTypeUntilFingerIsLifted() {
        var state = new VrKeyboardPressState();
        state.update(null, false, true, false, 0);
        assertEquals("q", state.update("q", true, false, false, 1));
        assertNull(state.update("w", true, false, false, 2));
        assertNull(state.update("e", true, false, false, 3));
        state.update("e", false, true, false, 4);
        assertEquals("e", state.update("e", true, false, false, 5));
    }

    @Test void triggerPressedOutsideKeysCannotDragIntoOne() {
        var state = new VrKeyboardPressState();
        state.update(null, false, true, false, 0);
        assertNull(state.update(null, true, false, false, 1));
        assertNull(state.update("enter", true, false, false, 2));
    }

    @Test void backspaceRepeatsAfterDelayAndStallsDoNotReplayBurst() {
        var state = new VrKeyboardPressState();
        state.update(null, false, true, false, 0);
        assertEquals("backspace", state.update("backspace", true, false, true, 1));
        assertNull(state.update("backspace", true, false, true, 400_000_001));
        assertEquals("backspace", state.update("backspace", true, false, true, 450_000_001));
        assertEquals("backspace", state.update("backspace", true, false, true, 5_000_000_000L));
        assertNull(state.update("backspace", true, false, true, 5_000_000_001L));
        assertNull(state.update("delete", true, false, true, 6_000_000_000L));
    }

    @Test void trackingLossOrInputModeSwitchDoesNotRetypeExistingContact() {
        var state = new VrKeyboardPressState();
        state.update(null, false, true, false, 0);
        assertEquals("a", state.update("a", true, false, false, 1));
        state.reset();
        assertFalse(state.isHeld("a"));
        assertNull(state.update("a", true, false, false, 2));
    }
}
