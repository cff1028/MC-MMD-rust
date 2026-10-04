package com.shiroha.mmdskin.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class VrHandConfigTest {
    @TempDir Path temporary;
    @AfterEach void restoreConfigRoot() {
        ModelConfigManager.invalidateAll();
        ModelConfigManager.setConfigRootDirSupplierForTesting(null);
    }

    @Test void oldModelConfigurationsKeepAutomaticTrackingAndNeutralIndependentThumbs() throws Exception {
        Path file = temporary.resolve("legacy.json");
        Files.writeString(file, "{\"modelScale\":1.2,\"hiddenMaterials\":[3]}");
        ModelConfigData loaded = ModelConfigData.load(file.toFile());
        assertTrue(loaded.vrFingerTrackingEnabled);
        assertTrue(loaded.vrLeftHandTrackingEnabled);
        assertTrue(loaded.vrRightHandTrackingEnabled);
        assertNotSame(loaded.vrLeftThumb, loaded.vrRightThumb);
        assertEquals(1, loaded.vrLeftThumb.baseCurlScale);
        assertEquals(1, loaded.vrRightThumb.middleCurlScale);
        assertEquals(0, loaded.vrLeftThumb.curlAxisOffsetDeg);
        assertEquals(1.2f, loaded.modelScale);
        assertTrue(loaded.hiddenMaterials.contains(3));
    }

    @Test void nullThumbObjectsFromEditedJsonRestoreNeutralValues() throws Exception {
        Path file = temporary.resolve("null.json");
        Files.writeString(file, "{\"vrLeftThumb\":null,\"vrRightThumb\":null,\"vrFingerTrackingEnabled\":false}");
        ModelConfigData loaded = ModelConfigData.load(file.toFile());
        assertFalse(loaded.vrFingerTrackingEnabled);
        assertNotNull(loaded.vrLeftThumb);
        assertNotNull(loaded.vrRightThumb);
        assertEquals(1, loaded.vrRightThumb.tipCurlScale);
        assertEquals(0, loaded.vrRightThumb.oppositionOffsetDeg);
    }

    @Test void editableCopiesCannotMutateCachedOrOppositeHandCalibration() {
        ModelConfigData source = new ModelConfigData();
        source.vrLeftThumb.curlAxisOffsetDeg = 35;
        source.vrRightThumb.baseCurlScale = 1.5f;
        ModelConfigData editable = source.copy();
        editable.vrLeftThumb.curlAxisOffsetDeg = -50;
        editable.vrRightThumb.baseCurlScale = 2;
        assertEquals(35, source.vrLeftThumb.curlAxisOffsetDeg);
        assertEquals(1.5f, source.vrRightThumb.baseCurlScale);
        assertEquals(0, source.vrRightThumb.curlAxisOffsetDeg);
        assertNotSame(source.vrLeftThumb, editable.vrLeftThumb);
        assertNotSame(source.vrRightThumb, editable.vrRightThumb);
    }

    @Test void badCalibrationValuesAreBoundedWithoutMutatingTheInputCopy() {
        ModelConfigData config = new ModelConfigData();
        config.vrLeftThumb.baseCurlScale = Float.NaN;
        config.vrLeftThumb.middleCurlScale = 8;
        config.vrLeftThumb.tipCurlScale = -2;
        config.vrLeftThumb.baseCurlOffsetDeg = -200;
        config.vrLeftThumb.oppositionOffsetDeg = 200;
        config.vrLeftThumb.curlAxisOffsetDeg = 999;
        config.vrRightThumb.curlAxisOffsetDeg = Float.POSITIVE_INFINITY;
        ModelConfigData normalized = config.normalizedCopy();
        assertEquals(1, normalized.vrLeftThumb.baseCurlScale);
        assertEquals(3, normalized.vrLeftThumb.middleCurlScale);
        assertEquals(0, normalized.vrLeftThumb.tipCurlScale);
        assertEquals(-90, normalized.vrLeftThumb.baseCurlOffsetDeg);
        assertEquals(90, normalized.vrLeftThumb.oppositionOffsetDeg);
        assertEquals(180, normalized.vrLeftThumb.curlAxisOffsetDeg);
        assertEquals(0, normalized.vrRightThumb.curlAxisOffsetDeg);
        assertEquals(999, config.vrLeftThumb.curlAxisOffsetDeg);
    }

    @Test void savingAndReloadingPreservesBothThumbsAndAllOtherModelSettings() {
        configureRoot();
        ModelConfigData original = sample();
        ModelConfigManager.saveConfig("avatar", original);
        ModelConfigManager.invalidate("avatar");
        ModelConfigData loaded = ModelConfigManager.getConfig("avatar");
        assertFalse(loaded.vrFingerTrackingEnabled);
        assertFalse(loaded.vrLeftHandTrackingEnabled);
        assertTrue(loaded.vrRightHandTrackingEnabled);
        assertThumb(original.vrLeftThumb, loaded.vrLeftThumb);
        assertThumb(original.vrRightThumb, loaded.vrRightThumb);
        assertEquals(original.modelScale, loaded.modelScale);
        assertEquals(original.vrArmLengthScale, loaded.vrArmLengthScale);
        assertEquals(original.vrEyeOffsetY, loaded.vrEyeOffsetY);
        assertEquals(original.heldItemRotationZ, loaded.heldItemRotationZ);
        assertEquals(original.hiddenMaterials, loaded.hiddenMaterials);
    }

    @Test void handPreviewAndCancelRestoreLiveDataWithoutWritingTheSavedFile() throws Exception {
        configureRoot();
        ModelConfigData original = sample();
        ModelConfigManager.saveConfig("avatar", original);
        byte[] disk = Files.readAllBytes(ModelConfigManager.getConfigFile("avatar").toPath());
        ModelConfigData edit = original.copy();
        edit.vrFingerTrackingEnabled = true;
        edit.vrLeftThumb.curlAxisOffsetDeg = -75;
        ModelConfigManager.previewConfig("avatar", edit);
        assertTrue(ModelConfigManager.getLiveConfig("avatar").vrFingerTrackingEnabled);
        assertEquals(-75, ModelConfigManager.getLiveConfig("avatar").vrLeftThumb.curlAxisOffsetDeg);
        edit.vrLeftThumb.curlAxisOffsetDeg = 0;
        assertEquals(-75, ModelConfigManager.getLiveConfig("avatar").vrLeftThumb.curlAxisOffsetDeg);
        assertArrayEquals(disk, Files.readAllBytes(ModelConfigManager.getConfigFile("avatar").toPath()));
        ModelConfigManager.previewConfig("avatar", original);
        assertFalse(ModelConfigManager.getLiveConfig("avatar").vrFingerTrackingEnabled);
        assertThumb(original.vrLeftThumb, ModelConfigManager.getLiveConfig("avatar").vrLeftThumb);
        assertArrayEquals(disk, Files.readAllBytes(ModelConfigManager.getConfigFile("avatar").toPath()));
    }

    @Test void quickTogglePreservesCalibrationAndOtherModels() {
        configureRoot();
        ModelConfigData original = sample();
        ModelConfigManager.saveConfig("avatar", original);
        ModelConfigManager.saveConfig("other-avatar", new ModelConfigData());
        ModelConfigData toggled = ModelConfigManager.getConfig("avatar");
        toggled.vrFingerTrackingEnabled = !toggled.vrFingerTrackingEnabled;
        ModelConfigManager.saveConfig("avatar", toggled);
        assertTrue(ModelConfigManager.getLiveConfig("avatar").vrFingerTrackingEnabled);
        assertThumb(original.vrLeftThumb, ModelConfigManager.getLiveConfig("avatar").vrLeftThumb);
        assertThumb(original.vrRightThumb, ModelConfigManager.getLiveConfig("avatar").vrRightThumb);
        assertEquals(original.vrArmIkStrength, ModelConfigManager.getLiveConfig("avatar").vrArmIkStrength);
        assertEquals(0, ModelConfigManager.getLiveConfig("other-avatar").vrLeftThumb.curlAxisOffsetDeg);
        assertTrue(ModelConfigManager.getLiveConfig("other-avatar").vrFingerTrackingEnabled);
    }

    private void configureRoot() { ModelConfigManager.setConfigRootDirSupplierForTesting(() -> temporary.toFile()); }
    private static ModelConfigData sample() {
        ModelConfigData config = new ModelConfigData();
        config.vrFingerTrackingEnabled = false;
        config.vrLeftHandTrackingEnabled = false;
        config.vrLeftThumb.baseCurlScale = 1.3f; config.vrLeftThumb.middleCurlScale = 1.6f; config.vrLeftThumb.tipCurlScale = 0.7f;
        config.vrLeftThumb.baseCurlOffsetDeg = 20; config.vrLeftThumb.oppositionOffsetDeg = -15; config.vrLeftThumb.curlAxisOffsetDeg = 80;
        config.vrRightThumb.baseCurlScale = 1.7f; config.vrRightThumb.middleCurlScale = 0.4f; config.vrRightThumb.tipCurlScale = 2.3f;
        config.vrRightThumb.baseCurlOffsetDeg = -30; config.vrRightThumb.oppositionOffsetDeg = 25; config.vrRightThumb.curlAxisOffsetDeg = -70;
        config.modelScale = 1.3f; config.vrArmLengthScale = 1.5f; config.vrArmIkStrength = 0.8f;
        config.vrEyeOffsetY = 0.1f; config.heldItemRotationZ = 37; config.hiddenMaterials.add(4);
        return config;
    }
    private static void assertThumb(ModelConfigData.ThumbCalibration expected, ModelConfigData.ThumbCalibration actual) {
        assertEquals(expected.baseCurlScale, actual.baseCurlScale);
        assertEquals(expected.middleCurlScale, actual.middleCurlScale);
        assertEquals(expected.tipCurlScale, actual.tipCurlScale);
        assertEquals(expected.baseCurlOffsetDeg, actual.baseCurlOffsetDeg);
        assertEquals(expected.oppositionOffsetDeg, actual.oppositionOffsetDeg);
        assertEquals(expected.curlAxisOffsetDeg, actual.curlAxisOffsetDeg);
    }
}
