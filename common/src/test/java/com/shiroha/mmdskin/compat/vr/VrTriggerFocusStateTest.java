package com.shiroha.mmdskin.compat.vr;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VrTriggerFocusStateTest {
    private VrTriggerFocusState ready() {
        var state = new VrTriggerFocusState();
        state.update(false, false, true, true, 0, 0);
        return state;
    }
    @Test void hoverFollowsLastClickAndDoesNotJumpToTheOtherHeldHand() {
        var s = ready();
        var left = s.update(true, false, true, true, 10, 0);
        assertTrue(left.pressed()); assertEquals(0, left.focus());
        var both = s.update(true, true, true, true, 10, 20);
        assertFalse(both.pressed()); assertEquals(0, both.owner());
        var release = s.update(false, true, true, true, 10, 20);
        assertTrue(release.released()); assertFalse(release.pressed()); assertEquals(-1, release.owner());
        assertEquals(0, release.focus());
        s.update(false, false, true, true, 10, 20);
        assertEquals(1, s.update(false, true, true, true, 10, 30).owner());
    }
    @Test void nativeTimestampsResolveTwoPressesInTheSameFrame() {
        assertEquals(0, ready().update(true, true, true, true, 100, 120).owner());
        assertEquals(1, ready().update(true, true, true, true, 120, 100).owner());
        assertEquals(1, ready().update(true, true, true, true, 100, 100).owner());
    }
    @Test void screenChangesAndTrackingLossRequireAReleasedTriggerBeforeNewPress() {
        var s = ready(); s.update(true, false, true, true, 1, 0); s.reset();
        assertFalse(s.update(true, false, true, true, 1, 0).pressed());
        s.update(false, false, true, true, 1, 0);
        assertTrue(s.update(true, false, true, true, 2, 0).pressed());
        assertTrue(s.update(true, false, false, true, 2, 0).released());
        assertFalse(s.update(true, false, true, true, 2, 0).pressed());
    }
    @Test void disablingLeftAndPressingAwayFromUiNeverSynthesizesClicks() {
        var s = ready();
        assertFalse(s.update(true, false, false, true, 1, 0).pressed());
        assertFalse(s.update(true, false, true, true, 1, 0).pressed());
        s.update(false, false, true, true, 1, 0);
        assertTrue(s.update(true, false, true, true, 2, 0).pressed());
        s.useRightHand();
        assertEquals(1, s.update(true, false, false, true, 2, 0).focus());
    }
    @Test void reversedHandMappingAlwaysUsesPhysicalHands() {
        for (boolean reverse : new boolean[]{false, true}) for (int physical = 0; physical < 2; physical++)
            assertEquals(physical, VivecraftUiInteraction.physicalHand(VivecraftUiInteraction.logicalHand(physical, reverse), reverse));
    }
}
