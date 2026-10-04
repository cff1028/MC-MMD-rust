package com.shiroha.mmdskin.compat.vr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VrRadialPageStateTest {
    @Test
    void holdNavigationStaysOpenWithoutRepeatedSelectionUntilAnotherPress() {
        var state = new VrRadialPageState();
        state.changePage(true);
        assertTrue(state.isMmdPage());
        assertTrue(state.consumeNavigationClose());
        assertFalse(state.consumeNavigationClose());
        assertTrue(state.isAwaitingHoldPress());
        assertFalse(state.resumeHoldSelection(false, true, false));
        assertTrue(state.isAwaitingHoldPress());
        assertTrue(state.resumeHoldSelection(false, true, true));
        assertFalse(state.isAwaitingHoldPress());
        assertFalse(state.resumeHoldSelection(false, true, true));
    }

    @Test
    void toggleModePagesDoNotInterceptNormalInput() {
        var state = new VrRadialPageState();
        state.changePage(false);
        assertTrue(state.isMmdPage());
        assertFalse(state.consumeNavigationClose());
        assertFalse(state.isAwaitingHoldPress());
        state.changePage(false);
        assertFalse(state.isMmdPage());
    }

    @Test
    void menuButtonAndScreenChangesCanStillDismissAPinnedPage() {
        var state = new VrRadialPageState();
        state.changePage(true);
        assertFalse(state.resumeHoldSelection(false, false, true));
        state.resetInteraction();
        assertFalse(state.isAwaitingHoldPress());
        assertFalse(state.consumeNavigationClose());
        assertTrue(state.isMmdPage());
    }

    @Test
    void reopeningKeepsChosenPageButClearsHeldInput() {
        var state = new VrRadialPageState();
        state.changePage(true);
        assertFalse(state.resumeHoldSelection(true, true, true));
        state.resetInteraction();
        assertFalse(state.isAwaitingHoldPress());
        assertTrue(state.isMmdPage());
        state.changePage(true);
        assertFalse(state.isMmdPage());
    }
}
