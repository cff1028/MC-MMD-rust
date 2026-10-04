package com.shiroha.mmdskin.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VrPointerConfigTest {
    @Test void leftInteractionAndHitOnlyCrosshairRoundTripAndPreviewCancel() {
        root();
        var original = VrPointerConfigManager.getConfig();
        assertFalse(original.leftTriggerInteraction);
        var draft = original.copy();
        draft.leftTriggerInteraction = true;
        draft.hideCrosshairMode = VrPointerConfigData.CrosshairMode.UI_HIT;
        VrPointerConfigManager.preview(draft);
        assertTrue(VrPointerConfigManager.getLiveConfig().leftTriggerInteraction);
        VrPointerConfigManager.preview(original);
        assertFalse(VrPointerConfigManager.getLiveConfig().leftTriggerInteraction);
        assertTrue(VrPointerConfigManager.save(draft));
        VrPointerConfigManager.invalidate();
        assertTrue(VrPointerConfigManager.getConfig().leftTriggerInteraction);
        assertEquals(VrPointerConfigData.CrosshairMode.UI_HIT, VrPointerConfigManager.getConfig().hideCrosshairMode);
    }
    @TempDir Path temporary;
    @AfterEach void restoreConfigRoot() { VrPointerConfigManager.setConfigRootDirSupplierForTesting(null); }

    @Test void defaultsRequireUiAndKeepBothHandsWithoutCreatingFiles() {
        root();
        VrPointerConfigData config = VrPointerConfigManager.getConfig();
        assertEquals(VrPointerConfigData.Visibility.UI_ONLY, config.visibility);
        assertEquals(VrPointerConfigData.PressBinding.TRIGGER, config.pressBinding);
        assertTrue(config.leftEnabled && config.rightEnabled && config.dotEnabled);
        assertEquals(VrPointerConfigData.CrosshairMode.OFF, config.hideCrosshairMode);
        assertEquals(.002f, config.rayWidth);
        assertEquals(.003f, config.dotRadiusView);
        assertEquals(8f, config.maxDistance);
        assertFalse(VrPointerConfigManager.getConfigFile().exists());
    }

    @Test void copiesAndPreviewsCannotLeakEditableMutationsToTheRenderSnapshot() {
        root();
        VrPointerConfigData draft = sample();
        VrPointerConfigManager.preview(draft);
        VrPointerConfigData snapshot = VrPointerConfigManager.getLiveConfig();
        draft.rayColor = 0;
        draft.leftEnabled = true;
        assertEquals(0x123456, snapshot.rayColor);
        assertFalse(snapshot.leftEnabled);
        VrPointerConfigData editor = VrPointerConfigManager.getConfig();
        editor.dotRadiusView = .049f;
        assertEquals(.012f, snapshot.dotRadiusView);
        assertNotSame(editor, snapshot);
        VrPointerConfigManager.preview(new VrPointerConfigData());
        assertEquals(.012f, snapshot.dotRadiusView, "Published old frame snapshots remain stable");
    }

    @Test void roundTripPreservesEveryGlobalPreference() {
        root();
        VrPointerConfigData config = sample();
        assertTrue(VrPointerConfigManager.save(config));
        VrPointerConfigManager.invalidate();
        assertConfig(config, VrPointerConfigManager.getConfig());
        assertEquals(temporary.resolve("vr_pointer.json"), VrPointerConfigManager.getConfigFile().toPath());
    }

    @Test void previewCancelAndReloadKeepTheSavedFileUntouched() throws Exception {
        root();
        VrPointerConfigData original = sample();
        assertTrue(VrPointerConfigManager.save(original));
        byte[] before = Files.readAllBytes(VrPointerConfigManager.getConfigFile().toPath());
        VrPointerConfigData draft = original.copy();
        draft.visibility = VrPointerConfigData.Visibility.ALWAYS;
        draft.rayColor = 0xFF0000;
        VrPointerConfigManager.preview(draft);
        assertEquals(VrPointerConfigData.Visibility.ALWAYS, VrPointerConfigManager.getLiveConfig().visibility);
        assertArrayEquals(before, Files.readAllBytes(VrPointerConfigManager.getConfigFile().toPath()));
        VrPointerConfigManager.preview(original);
        assertConfig(original, VrPointerConfigManager.getConfig());
        VrPointerConfigManager.invalidate();
        assertConfig(original, VrPointerConfigManager.getConfig());
        assertArrayEquals(before, Files.readAllBytes(VrPointerConfigManager.getConfigFile().toPath()));
    }

    @Test void oldPartialAndUnknownEnumValuesUseSafeDefaults() throws Exception {
        root();
        Files.writeString(VrPointerConfigManager.getConfigFile().toPath(),
                "{\"visibility\":\"old-mode\",\"pressBinding\":null,\"rayColor\":66051,\"leftEnabled\":false}");
        VrPointerConfigData config = VrPointerConfigManager.getConfig();
        assertEquals(VrPointerConfigData.Visibility.UI_ONLY, config.visibility);
        assertEquals(VrPointerConfigData.PressBinding.TRIGGER, config.pressBinding);
        assertFalse(config.leftEnabled);
        assertTrue(config.rightEnabled);
        assertEquals(0x010203, config.rayColor);
        assertEquals(.0045f, config.pressedDotRadiusView);
    }

    @Test void legacyMetreRadiiMigrateToViewFractionsWithoutRewritingTheFile() throws Exception {
        root();
        String legacy = "{\"dotRadius\":0.012,\"pressedDotRadius\":0.026,\"rayColor\":66051,\"leftEnabled\":false}";
        Path file = VrPointerConfigManager.getConfigFile().toPath();
        Files.writeString(file, legacy);
        VrPointerConfigData loaded = VrPointerConfigManager.getConfig();
        assertEquals(.006f, loaded.dotRadiusView, 1e-8f);
        assertEquals(.013f, loaded.pressedDotRadiusView, 1e-8f);
        assertEquals(0x010203, loaded.rayColor);
        assertFalse(loaded.leftEnabled);
        assertEquals(legacy, Files.readString(file));
        VrPointerConfigManager.preview(loaded);
        assertEquals(legacy, Files.readString(file), "Preview and migration must not save automatically");
    }

    @Test void newViewFieldsTakePriorityIndividuallyAndMigrateOnlyMissingFields() throws Exception {
        root();
        Path file = VrPointerConfigManager.getConfigFile().toPath();
        Files.writeString(file, "{\"dotRadius\":0.04,\"pressedDotRadius\":0.018,\"dotRadiusView\":0.002}");
        VrPointerConfigData loaded = VrPointerConfigManager.getConfig();
        assertEquals(.002f, loaded.dotRadiusView);
        assertEquals(.009f, loaded.pressedDotRadiusView, 1e-8f);
        VrPointerConfigManager.invalidate();
        Files.writeString(file, "{\"dotRadius\":0.04,\"pressedDotRadius\":0.018,\"dotRadiusView\":null,\"pressedDotRadiusView\":0.004}");
        loaded = VrPointerConfigManager.getConfig();
        assertEquals(.003f, loaded.dotRadiusView, "Explicit new null uses the safe default, never the legacy radius");
        assertEquals(.004f, loaded.pressedDotRadiusView);
    }

    @Test void invalidLegacySizesDoNotDiscardOtherPreferencesAndMigrationClampsBounds() throws Exception {
        root();
        Path file = VrPointerConfigManager.getConfigFile().toPath();
        Files.writeString(file, "{\"dotRadius\":\"broken\",\"pressedDotRadius\":100,\"visibility\":\"UI_HIT\",\"hideOriginalCrosshair\":true}");
        VrPointerConfigData loaded = VrPointerConfigManager.getConfig();
        assertEquals(.003f, loaded.dotRadiusView);
        assertEquals(.025f, loaded.pressedDotRadiusView);
        assertEquals(VrPointerConfigData.Visibility.UI_HIT, loaded.visibility);
        assertEquals(VrPointerConfigData.CrosshairMode.ALWAYS, loaded.hideCrosshairMode);
    }

    @Test void explicitSaveUsesOnlyViewRadiusFieldsAndReloadNeverConvertsThemAgain() throws Exception {
        root();
        Path file = VrPointerConfigManager.getConfigFile().toPath();
        Files.writeString(file, "{\"dotRadius\":0.02,\"pressedDotRadius\":0.03}");
        VrPointerConfigData loaded = VrPointerConfigManager.getConfig();
        assertTrue(VrPointerConfigManager.save(loaded));
        String saved = Files.readString(file);
        assertTrue(saved.contains("\"dotRadiusView\"") && saved.contains("\"pressedDotRadiusView\""));
        assertFalse(saved.contains("\"dotRadius\"") || saved.contains("\"pressedDotRadius\""));
        VrPointerConfigManager.invalidate();
        VrPointerConfigData reloaded = VrPointerConfigManager.getConfig();
        assertEquals(.01f, reloaded.dotRadiusView, 1e-8f);
        assertEquals(.015f, reloaded.pressedDotRadiusView, 1e-8f);
    }

    @Test void hidingOriginalCrosshairAndUiHitVisibilityAreIndependentPersistentPreferences() {
        root();
        VrPointerConfigData config = new VrPointerConfigData();
        config.visibility = VrPointerConfigData.Visibility.OFF;
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.ALWAYS;
        assertTrue(VrPointerConfigManager.save(config));
        VrPointerConfigManager.invalidate();
        assertEquals(VrPointerConfigData.Visibility.OFF, VrPointerConfigManager.getConfig().visibility);
        assertEquals(VrPointerConfigData.CrosshairMode.ALWAYS, VrPointerConfigManager.getConfig().hideCrosshairMode);
        config.visibility = VrPointerConfigData.Visibility.UI_HIT;
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.UI_ONLY;
        assertTrue(VrPointerConfigManager.save(config));
        VrPointerConfigManager.invalidate();
        assertEquals(VrPointerConfigData.Visibility.UI_HIT, VrPointerConfigManager.getConfig().visibility);
        assertEquals(VrPointerConfigData.CrosshairMode.UI_ONLY, VrPointerConfigManager.getConfig().hideCrosshairMode);
    }

    @Test void corruptFileFallsBackWithoutOverwritingTheOriginal() throws Exception {
        root();
        String corrupt = "{broken user edit";
        Files.writeString(VrPointerConfigManager.getConfigFile().toPath(), corrupt);
        assertEquals(VrPointerConfigData.Visibility.UI_ONLY, VrPointerConfigManager.getConfig().visibility);
        assertEquals(corrupt, Files.readString(VrPointerConfigManager.getConfigFile().toPath()));
    }

    @Test void legacyCrosshairBooleanMigratesBothValuesWithoutWritingUntilSave() throws Exception {
        root();
        Path file = VrPointerConfigManager.getConfigFile().toPath();
        for (boolean legacy : new boolean[]{true, false}) {
            String json = "{\"hideOriginalCrosshair\":" + legacy + ",\"rayColor\":66051}";
            Files.writeString(file, json);
            VrPointerConfigManager.invalidate();
            VrPointerConfigData config = VrPointerConfigManager.getConfig();
            assertEquals(legacy ? VrPointerConfigData.CrosshairMode.ALWAYS : VrPointerConfigData.CrosshairMode.OFF,
                    config.hideCrosshairMode);
            assertEquals(0x010203, config.rayColor);
            assertEquals(json, Files.readString(file));
            assertTrue(VrPointerConfigManager.save(config));
            assertFalse(Files.readString(file).contains("hideOriginalCrosshair"));
            VrPointerConfigManager.invalidate();
            assertEquals(config.hideCrosshairMode, VrPointerConfigManager.getConfig().hideCrosshairMode);
        }
    }

    @Test void explicitCrosshairModesOverrideTheLegacyBooleanAndInvalidModesAreSafe() throws Exception {
        root();
        Path file = VrPointerConfigManager.getConfigFile().toPath();
        for (String mode : new String[]{"\"UI_ONLY\"", "\"OFF\"", "null", "\"unknown\""}) {
            Files.writeString(file, "{\"hideOriginalCrosshair\":true,\"hideCrosshairMode\":" + mode + "}");
            VrPointerConfigManager.invalidate();
            assertEquals(mode.equals("\"UI_ONLY\"") ? VrPointerConfigData.CrosshairMode.UI_ONLY : VrPointerConfigData.CrosshairMode.OFF,
                    VrPointerConfigManager.getConfig().hideCrosshairMode);
        }
        Files.writeString(file, "{\"hideOriginalCrosshair\":\"corrupt\",\"rayColor\":66051}");
        VrPointerConfigManager.invalidate();
        assertEquals(VrPointerConfigData.CrosshairMode.OFF, VrPointerConfigManager.getConfig().hideCrosshairMode);
        assertEquals(0x010203, VrPointerConfigManager.getConfig().rayColor);
    }

    @Test void invalidDimensionsTimingsAndColorsAreNormalizedInADefensiveCopy() {
        VrPointerConfigData config = new VrPointerConfigData();
        config.visibility = null; config.pressBinding = null;
        config.rayColor = 0xFF123456; config.dotColor = -1;
        config.pressedRayColor = 0xAA334455; config.pressedDotColor = -1;
        config.rayAlpha = Float.NaN; config.dotAlpha = 9;
        config.rayWidth = -3; config.maxDistance = Float.POSITIVE_INFINITY;
        config.dotRadiusView = 10; config.pressedDotRadiusView = -.2f;
        config.colorDurationMs = -1; config.radiusDurationMs = 4000;
        config.fadeInMs = Float.NEGATIVE_INFINITY; config.fadeOutMs = 9999;
        VrPointerConfigData normalized = config.normalizedCopy();
        assertEquals(0x123456, normalized.rayColor); assertEquals(0xFFFFFF, normalized.dotColor);
        assertEquals(0x334455, normalized.pressedRayColor); assertEquals(0xFFFFFF, normalized.pressedDotColor);
        assertEquals(.65f, normalized.rayAlpha); assertEquals(1, normalized.dotAlpha);
        assertEquals(.0005f, normalized.rayWidth); assertEquals(8, normalized.maxDistance);
        assertEquals(.025f, normalized.dotRadiusView); assertEquals(.0005f, normalized.pressedDotRadiusView);
        assertEquals(0, normalized.colorDurationMs); assertEquals(2000, normalized.radiusDurationMs);
        assertEquals(100, normalized.fadeInMs); assertEquals(2000, normalized.fadeOutMs);
        assertEquals(-3, config.rayWidth);
        assertNull(config.visibility);
    }

    @Test void zeroDurationAndInvisibleStylesAreValidPreferences() {
        VrPointerConfigData config = new VrPointerConfigData();
        config.rayAlpha = config.dotAlpha = 0;
        config.colorDurationMs = config.radiusDurationMs = config.fadeInMs = config.fadeOutMs = 0;
        config.visibility = VrPointerConfigData.Visibility.OFF;
        config.dotEnabled = false;
        VrPointerConfigData normalized = config.normalizedCopy();
        assertEquals(0, normalized.rayAlpha); assertEquals(0, normalized.dotAlpha);
        assertEquals(0, normalized.fadeOutMs); assertEquals(0, normalized.colorDurationMs);
        assertEquals(VrPointerConfigData.Visibility.OFF, normalized.visibility);
        assertFalse(normalized.dotEnabled);
    }

    @Test void failedSaveRetainsTheCurrentLiveSnapshot() throws Exception {
        Path blockedDirectory = temporary.resolve("not-a-directory");
        Files.writeString(blockedDirectory, "keep");
        VrPointerConfigManager.setConfigRootDirSupplierForTesting(() -> blockedDirectory.toFile());
        VrPointerConfigManager.preview(sample());
        assertFalse(VrPointerConfigManager.save(new VrPointerConfigData()));
        assertConfig(sample(), VrPointerConfigManager.getLiveConfig());
        assertEquals("keep", Files.readString(blockedDirectory));
    }

    private void root() { VrPointerConfigManager.setConfigRootDirSupplierForTesting(() -> temporary.toFile()); }
    private static VrPointerConfigData sample() {
        VrPointerConfigData config = new VrPointerConfigData();
        config.visibility = VrPointerConfigData.Visibility.OFF;
        config.leftEnabled = false; config.rightEnabled = true; config.dotEnabled = false;
        config.hideCrosshairMode = VrPointerConfigData.CrosshairMode.ALWAYS;
        config.pressBinding = VrPointerConfigData.PressBinding.TRIGGER_OR_GRIP;
        config.rayColor = 0x123456; config.rayAlpha = .31f; config.rayWidth = .004f; config.maxDistance = 14;
        config.dotColor = 0x654321; config.dotAlpha = .78f; config.dotRadiusView = .012f;
        config.pressedRayColor = 0xAABBCC; config.pressedDotColor = 0xDDEEFF;
        config.colorAnimationEnabled = false; config.colorDurationMs = 670;
        config.pressedDotRadiusView = .025f; config.radiusAnimationEnabled = false; config.radiusDurationMs = 320;
        config.fadeInMs = 240; config.fadeOutMs = 910;
        return config;
    }
    private static void assertConfig(VrPointerConfigData expected, VrPointerConfigData actual) {
        assertEquals(expected.visibility, actual.visibility);
        assertEquals(expected.leftEnabled, actual.leftEnabled); assertEquals(expected.rightEnabled, actual.rightEnabled);
        assertEquals(expected.dotEnabled, actual.dotEnabled); assertEquals(expected.pressBinding, actual.pressBinding);
        assertEquals(expected.hideCrosshairMode, actual.hideCrosshairMode);
        assertEquals(expected.rayColor, actual.rayColor); assertEquals(expected.rayAlpha, actual.rayAlpha);
        assertEquals(expected.rayWidth, actual.rayWidth); assertEquals(expected.maxDistance, actual.maxDistance);
        assertEquals(expected.dotColor, actual.dotColor); assertEquals(expected.dotAlpha, actual.dotAlpha);
        assertEquals(expected.dotRadiusView, actual.dotRadiusView); assertEquals(expected.pressedDotRadiusView, actual.pressedDotRadiusView);
        assertEquals(expected.pressedRayColor, actual.pressedRayColor); assertEquals(expected.pressedDotColor, actual.pressedDotColor);
        assertEquals(expected.colorAnimationEnabled, actual.colorAnimationEnabled);
        assertEquals(expected.colorDurationMs, actual.colorDurationMs);
        assertEquals(expected.radiusAnimationEnabled, actual.radiusAnimationEnabled);
        assertEquals(expected.radiusDurationMs, actual.radiusDurationMs);
        assertEquals(expected.fadeInMs, actual.fadeInMs); assertEquals(expected.fadeOutMs, actual.fadeOutMs);
    }
}
