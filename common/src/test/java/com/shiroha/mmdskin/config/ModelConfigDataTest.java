package com.shiroha.mmdskin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigDataTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldClampHeldItemScaleIntoSupportedRange() {
        ModelConfigData data = new ModelConfigData();
        data.heldItemScale = 99.0f;

        ModelConfigData normalized = data.normalizeInPlace();

        assertEquals(ModelConfigData.MAX_HELD_ITEM_SCALE, normalized.heldItemScale);
    }

    @Test
    void shouldFallbackHeldItemScaleWhenValueIsNotFinite() {
        ModelConfigData data = new ModelConfigData();
        data.heldItemScale = Float.NaN;

        ModelConfigData normalized = data.normalizeInPlace();

        assertEquals(ModelConfigData.DEFAULT_HELD_ITEM_SCALE, normalized.heldItemScale);
    }

    @Test
    void shouldLoadLegacyHeldItemScaleAlias() throws Exception {
        Path configFile = tempDir.resolve("legacy-model.json");
        Files.writeString(configFile, """
                {
                  "firstPersonHeldBlockScale": 1.75
                }
                """, StandardCharsets.UTF_8);

        ModelConfigData loaded = ModelConfigData.load(configFile.toFile());

        assertEquals(1.75f, loaded.heldItemScale);
    }

    @Test
    void shouldDropNegativeHiddenMaterialIndices() {
        ModelConfigData data = new ModelConfigData();
        data.hiddenMaterials.add(-1);
        data.hiddenMaterials.add(2);

        ModelConfigData normalized = data.normalizeInPlace();

        assertEquals(1, normalized.hiddenMaterials.size());
        assertTrue(normalized.hiddenMaterials.contains(2));
    }

    @Test
    void shouldDefaultNewVrSettingsForExistingModelFiles() throws Exception {
        Path configFile = tempDir.resolve("existing-model.json");
        Files.writeString(configFile, "{\"modelScale\":1.2,\"hiddenMaterials\":[3]}", StandardCharsets.UTF_8);

        ModelConfigData loaded = ModelConfigData.load(configFile.toFile());

        assertEquals(1.2f, loaded.modelScale);
        assertEquals(1.0f, loaded.vrArmIkStrength);
        assertEquals(1.0f, loaded.vrArmLengthScale);
        assertEquals(0.0f, loaded.vrEyeOffsetX);
        assertEquals(0.0f, loaded.heldItemOffsetY);
        assertEquals(0.0f, loaded.heldItemRotationZ);
        assertTrue(loaded.hiddenMaterials.contains(3));
    }

    @Test
    void shouldPersistVrCalibrationAndItemsWithoutLosingExistingSettings() {
        ModelConfigData source = new ModelConfigData();
        source.eyeTrackingEnabled = false;
        source.eyeMaxAngle = 0.3f;
        source.modelScale = 1.2f;
        source.vrArmIkStrength = 0.65f;
        source.vrArmLengthScale = 1.15f;
        source.vrEyeOffsetX = 0.12f;
        source.vrEyeOffsetY = -0.15f;
        source.vrEyeOffsetZ = 0.2f;
        source.heldItemScale = 1.3f;
        source.heldItemOffsetX = 0.3f;
        source.heldItemOffsetY = -0.4f;
        source.heldItemOffsetZ = 0.5f;
        source.heldItemRotationX = 30.0f;
        source.heldItemRotationY = -45.0f;
        source.heldItemRotationZ = 90.0f;
        source.hiddenMaterials.add(8);
        Path configFile = tempDir.resolve("vr-model.json");

        source.normalizedCopy().save(configFile.toFile());
        ModelConfigData loaded = ModelConfigData.load(configFile.toFile());

        assertEquals(source.eyeTrackingEnabled, loaded.eyeTrackingEnabled);
        assertEquals(source.eyeMaxAngle, loaded.eyeMaxAngle);
        assertEquals(source.modelScale, loaded.modelScale);
        assertEquals(source.vrArmIkStrength, loaded.vrArmIkStrength);
        assertEquals(source.vrArmLengthScale, loaded.vrArmLengthScale);
        assertEquals(source.vrEyeOffsetX, loaded.vrEyeOffsetX);
        assertEquals(source.vrEyeOffsetY, loaded.vrEyeOffsetY);
        assertEquals(source.vrEyeOffsetZ, loaded.vrEyeOffsetZ);
        assertEquals(source.heldItemScale, loaded.heldItemScale);
        assertEquals(source.heldItemOffsetX, loaded.heldItemOffsetX);
        assertEquals(source.heldItemOffsetY, loaded.heldItemOffsetY);
        assertEquals(source.heldItemOffsetZ, loaded.heldItemOffsetZ);
        assertEquals(source.heldItemRotationX, loaded.heldItemRotationX);
        assertEquals(source.heldItemRotationY, loaded.heldItemRotationY);
        assertEquals(source.heldItemRotationZ, loaded.heldItemRotationZ);
        assertEquals(source.hiddenMaterials, loaded.hiddenMaterials);
    }

    @Test
    void shouldKeepMalformedVrSettingsOutOfTheRenderer() {
        ModelConfigData data = new ModelConfigData();
        data.vrEyeOffsetX = Float.NaN;
        data.vrEyeOffsetY = 999.0f;
        data.vrEyeOffsetZ = -999.0f;
        data.vrArmIkStrength = Float.NEGATIVE_INFINITY;
        data.vrArmLengthScale = Float.NaN;
        data.heldItemOffsetX = Float.POSITIVE_INFINITY;
        data.heldItemOffsetY = -99.0f;
        data.heldItemOffsetZ = 99.0f;
        data.heldItemRotationX = Float.NaN;
        data.heldItemRotationY = -999.0f;
        data.heldItemRotationZ = 999.0f;

        ModelConfigData normalized = data.normalizedCopy();

        assertEquals(0.0f, normalized.vrEyeOffsetX);
        assertEquals(0.5f, normalized.vrEyeOffsetY);
        assertEquals(-0.5f, normalized.vrEyeOffsetZ);
        assertEquals(1.0f, normalized.vrArmIkStrength);
        assertEquals(1.0f, normalized.vrArmLengthScale);
        assertEquals(0.0f, normalized.heldItemOffsetX);
        assertEquals(-1.0f, normalized.heldItemOffsetY);
        assertEquals(1.0f, normalized.heldItemOffsetZ);
        assertEquals(0.0f, normalized.heldItemRotationX);
        assertEquals(-180.0f, normalized.heldItemRotationY);
        assertEquals(180.0f, normalized.heldItemRotationZ);
    }

    @Test
    void shouldBoundVrArmLengthIndependentlyOfFollowingStrength() {
        ModelConfigData config = new ModelConfigData();
        config.vrArmIkStrength = 0.25f;
        config.vrArmLengthScale = 8.0f;
        ModelConfigData normalized = config.normalizedCopy();
        assertEquals(4.0f, normalized.vrArmLengthScale);
        assertEquals(0.25f, normalized.vrArmIkStrength);
        config.vrArmLengthScale = -2.0f;
        assertEquals(0.25f, config.normalizedCopy().vrArmLengthScale);
    }
}
