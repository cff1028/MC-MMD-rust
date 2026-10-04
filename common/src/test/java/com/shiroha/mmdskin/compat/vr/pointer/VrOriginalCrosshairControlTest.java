package com.shiroha.mmdskin.compat.vr.pointer;

import com.shiroha.mmdskin.config.VrPointerConfigData;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class VrOriginalCrosshairControlTest {
    @Test void hitOnlyUsesCurrentIntersectionAndRestoresWhileTheUiStaysOpen() {
        var config = new VrPointerConfigData();
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.UI_HIT;
        var hit = new AtomicBoolean(false);
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> true, () -> true, hit::get));
        hit.set(true);
        assertTrue(VrOriginalCrosshairControl.shouldHide(config, () -> true, () -> true, hit::get));
        hit.set(false);
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> true, () -> true, hit::get));
        hit.set(true);
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> false, () -> true, hit::get));
    }
    @Test void defaultAndDisabledSwitchDoNotEvenQueryOptionalVrRuntime() {
        VrPointerConfigData config = new VrPointerConfigData();
        assertEquals(VrPointerConfigData.CrosshairMode.OFF, config.hideCrosshairMode);
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> {
            fail("disabled switch must not access an optional VR runtime"); return true;
        }, () -> { fail("disabled switch must not query UI state"); return true; }));
    }

    @Test void liveVrTransitionsRestoreDesktopCrosshairWithoutChangingSettings() {
        VrPointerConfigData config = new VrPointerConfigData();
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.ALWAYS;
        AtomicBoolean active = new AtomicBoolean(false);
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, active::get, () -> false));
        active.set(true);
        assertTrue(VrOriginalCrosshairControl.shouldHide(config, active::get, () -> false));
        active.set(false);
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, active::get, () -> false));
        assertEquals(VrPointerConfigData.CrosshairMode.ALWAYS, config.hideCrosshairMode, "leaving VR must not edit the saved preference");
    }

    @Test void originalCursorSwitchIsIndependentOfAuxiliaryPointerVisibilityOrTracking() {
        VrPointerConfigData config = new VrPointerConfigData();
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.ALWAYS;
        config.visibility = VrPointerConfigData.Visibility.OFF;
        config.leftEnabled = config.rightEnabled = config.dotEnabled = false;
        assertTrue(VrOriginalCrosshairControl.shouldHide(config, () -> true, () -> false));
        assertEquals(VrPointerConfigData.Visibility.OFF, config.visibility);
        assertFalse(config.dotEnabled);
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.OFF;
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> true, () -> true));
    }

    @Test void unavailableRuntimeKeepsOriginalCrosshairVisible() {
        VrPointerConfigData config = new VrPointerConfigData();
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.ALWAYS;
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> { throw new NoClassDefFoundError("optional Vivecraft absent"); }, () -> true));
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> { throw new IllegalStateException("runtime not ready"); }, () -> true));
    }

    @Test void uiOnlyRestoresTheGameplayCrosshairAsSoonAsTheUiCloses() {
        VrPointerConfigData config = new VrPointerConfigData();
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.UI_ONLY;
        AtomicBoolean uiOpen = new AtomicBoolean(false);
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> true, uiOpen::get));
        uiOpen.set(true);
        assertTrue(VrOriginalCrosshairControl.shouldHide(config, () -> true, uiOpen::get));
        uiOpen.set(false);
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> true, uiOpen::get));
        assertEquals(VrPointerConfigData.CrosshairMode.UI_ONLY, config.hideCrosshairMode);
    }

    @Test void desktopUiAndUnavailableUiDetectionNeverHideTheCrosshair() {
        VrPointerConfigData config = new VrPointerConfigData();
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.UI_ONLY;
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> false, () -> true));
        assertFalse(VrOriginalCrosshairControl.shouldHide(config, () -> true, () -> { throw new LinkageError("optional UI unavailable"); }));
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.ALWAYS;
        assertTrue(VrOriginalCrosshairControl.shouldHide(config, () -> true, () -> { fail("always mode must not depend on UI"); return false; }));
    }
}
