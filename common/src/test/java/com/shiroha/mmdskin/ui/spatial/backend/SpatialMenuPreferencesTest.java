package com.shiroha.mmdskin.ui.spatial.backend;

import com.google.gson.Gson;
import com.shiroha.mmdskin.ui.spatial.SpatialMenuTransforms.DetailBehavior;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialMenuPreferencesTest {
    @Test void olderPreferencesKeepTheirValuesAndReceiveSafeMotionDefaults() {
        var prefs = new Gson().fromJson("{\"panelDistance\":2.5,\"panelScale\":1.1}", SpatialMenuPreferences.class);
        prefs.normalize();
        assertEquals(2.5, prefs.panelDistance);
        assertEquals(1.1, prefs.panelScale);
        assertEquals(DetailBehavior.FIXED_CLOSE, prefs.detailBehavior);
        assertEquals(180, prefs.positionSmoothingMs);
        assertEquals(180, prefs.rotationSmoothingMs);
        assertTrue(prefs.closeDistance > prefs.panelDistance * 2);
    }

    @Test void changingOpeningDistanceRaisesAnUnsafeClosingThresholdWithoutLoweringACustomOne() {
        var prefs = new SpatialMenuPreferences();
        prefs.panelDistance = 2.5;
        prefs.closeDistance = 5;
        prefs.normalize();
        assertEquals(5.1, prefs.closeDistance, 1e-8);
        prefs.closeDistance = 12;
        prefs.panelDistance = .6;
        prefs.normalize();
        assertEquals(12, prefs.closeDistance);
    }

    @Test void userDistanceEditsScaleTheClosingRadiusInBothDirections() {
        var prefs = new SpatialMenuPreferences();
        double ratio = prefs.closeDistance / prefs.panelDistance;
        prefs.setPanelDistance(1.5);
        assertEquals(1.5 * ratio, prefs.closeDistance, 1e-8);
        prefs.setPanelDistance(.9);
        assertEquals(.9 * ratio, prefs.closeDistance, 1e-8);
        prefs.normalize();
        assertEquals(.9 * ratio, prefs.closeDistance, 1e-8, "Loading/normalization must not rescale repeatedly");
    }

    @Test void customClosingRadiusBecomesTheNewRatioAndStillHonorsStrictLimits() {
        var prefs = new SpatialMenuPreferences();
        prefs.closeDistance = 9;
        prefs.normalize();
        prefs.setPanelDistance(2.5);
        assertEquals(9 * 2.5 / 1.35, prefs.closeDistance, 1e-8);
        prefs.closeDistance = 32;
        prefs.panelDistance = .6;
        prefs.setPanelDistance(2.5);
        assertEquals(32, prefs.closeDistance);
        prefs.closeDistance = 5.1;
        prefs.setPanelDistance(.6);
        assertTrue(prefs.closeDistance > prefs.panelDistance * 2);
        assertEquals(1.3, prefs.closeDistance, 1e-8);
    }

    @Test void invalidSavedNumbersAndUnknownModeCannotPoisonTheSpatialPose() {
        var prefs = new Gson().fromJson("{\"detailBehavior\":\"removed_mode\",\"favorites\":null}", SpatialMenuPreferences.class);
        prefs.panelDistance = Double.NaN;
        prefs.closeDistance = Double.NEGATIVE_INFINITY;
        prefs.positionSmoothingMs = Double.POSITIVE_INFINITY;
        prefs.rotationSmoothingMs = -20;
        prefs.normalize();
        assertEquals(DetailBehavior.FIXED_CLOSE, prefs.detailBehavior);
        assertEquals(1.35, prefs.panelDistance);
        assertEquals(4, prefs.closeDistance);
        assertEquals(180, prefs.positionSmoothingMs);
        assertEquals(0, prefs.rotationSmoothingMs);
        assertNotNull(prefs.favorites);
    }
}
