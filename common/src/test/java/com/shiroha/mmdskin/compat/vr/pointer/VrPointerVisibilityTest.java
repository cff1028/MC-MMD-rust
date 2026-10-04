package com.shiroha.mmdskin.compat.vr.pointer;

import com.shiroha.mmdskin.config.VrPointerConfigData.Visibility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VrPointerVisibilityTest {
    @Test void uiHitModeRequiresThatHandsActualIntersection() {
        assertTrue(VrPointerVisibility.visible(Visibility.UI_HIT, true, true, true, true));
        assertFalse(VrPointerVisibility.visible(Visibility.UI_HIT, true, true, true, false));
        assertTrue(VrPointerVisibility.visible(Visibility.UI_HIT, true, true, false, true)); // World-space mirror.
        assertFalse(VrPointerVisibility.visible(Visibility.UI_HIT, true, true, false, false));
    }

    @Test void trackingAndPerHandSwitchesAlwaysApply() {
        for (Visibility mode : Visibility.values()) {
            assertFalse(VrPointerVisibility.visible(mode, false, true, true, true));
            assertFalse(VrPointerVisibility.visible(mode, true, false, true, true));
        }
    }

    @Test void existingModesKeepTheirMeaning() {
        assertTrue(VrPointerVisibility.visible(Visibility.ALWAYS, true, true, false, false));
        assertTrue(VrPointerVisibility.visible(Visibility.UI_ONLY, true, true, true, false));
        assertFalse(VrPointerVisibility.visible(Visibility.UI_ONLY, true, true, false, false));
        assertFalse(VrPointerVisibility.visible(Visibility.OFF, true, true, true, true));
        assertFalse(VrPointerVisibility.visible(null, true, true, true, true));
    }
}
