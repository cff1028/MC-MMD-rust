package com.shiroha.mmdskin.compat.vr.keyboard;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VrKeyboardKeyStateTest {
    @Test void simultaneousFingersOnOneKeyTypeOnceAndRemainDownUntilAllHaveLifted() {
        var state = new VrKeyboardKeyState();
        assertEquals(List.of("a"), state.update(List.of("a", "a"), List.of("a", "a"), k -> false, 0));
        assertTrue(state.update(List.of("a"), List.of(), k -> false, 1).isEmpty());
        assertTrue(state.update(List.of("a", "a"), List.of("a"), k -> false, 2).isEmpty());
        state.update(List.of(), List.of(), k -> false, 3);
        assertEquals(List.of("a"), state.update(List.of("a"), List.of("a"), k -> false, 4));
    }

    @Test void multipleFingersCannotMultiplyBackspaceRepeatAndStallsDoNotReplay() {
        var state = new VrKeyboardKeyState();
        assertEquals(List.of("backspace"), state.update(List.of("backspace", "backspace"), List.of("backspace"), k -> true, 0));
        assertTrue(state.update(List.of("backspace"), List.of(), k -> true, 400_000_000).isEmpty());
        assertEquals(List.of("backspace"), state.update(List.of("backspace", "backspace"), List.of("backspace"), k -> true, 450_000_000));
        assertEquals(List.of("backspace"), state.update(List.of("backspace"), List.of(), k -> true, 5_000_000_000L));
        assertTrue(state.update(List.of("backspace"), List.of(), k -> true, 5_000_000_001L).isEmpty());
    }

    @Test void distinctKeysRemainIndependentAndSlidingBackDoesNotRetype() {
        var state = new VrKeyboardKeyState();
        assertEquals(List.of("a", "b"), state.update(List.of("a", "b", "a"), List.of("a", "b"), k -> false, 0));
        state.update(List.of(), List.of(), k -> false, 1);
        assertTrue(state.update(List.of("a"), List.of(), k -> false, 2).isEmpty());
        state.reset();
        assertEquals(List.of("a"), state.update(List.of("a"), List.of("a"), k -> false, 3));
    }
}
