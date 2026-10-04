/* 文件职责：验证模型配置管理器的副本语义、保存归一化与空值回退。 */
package com.shiroha.mmdskin.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModelConfigManagerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        ModelConfigManager.invalidateAll();
        ModelConfigManager.setConfigRootDirSupplierForTesting(null);
    }

    @Test
    void shouldReturnDefensiveCopyWithoutMutatingCachedLiveConfig() {
        ModelConfigManager.setConfigRootDirSupplierForTesting(() -> tempDir.toFile());

        ModelConfigData source = new ModelConfigData();
        source.heldItemScale = 1.25f;
        ModelConfigManager.saveConfig("demo", source);

        ModelConfigData editable = ModelConfigManager.getConfig("demo");
        ModelConfigData live = ModelConfigManager.getLiveConfig("demo");

        assertNotSame(editable, live);
        editable.heldItemScale = 2.0f;

        assertEquals(1.25f, ModelConfigManager.getLiveConfig("demo").heldItemScale);
    }

    @Test
    void shouldNormalizeAndFallbackWhenSavingNullConfig() {
        ModelConfigManager.setConfigRootDirSupplierForTesting(() -> tempDir.toFile());

        ModelConfigData outOfRange = new ModelConfigData();
        outOfRange.heldItemScale = 99.0f;
        ModelConfigManager.saveConfig("normalized", outOfRange);

        assertEquals(ModelConfigData.MAX_HELD_ITEM_SCALE, ModelConfigManager.getLiveConfig("normalized").heldItemScale);

        ModelConfigManager.saveConfig("fallback", null);
        assertEquals(ModelConfigData.DEFAULT_HELD_ITEM_SCALE, ModelConfigManager.getLiveConfig("fallback").heldItemScale);
    }

    @Test
    void shouldPreviewAndRestoreWithoutTouchingSavedConfiguration() throws Exception {
        ModelConfigManager.setConfigRootDirSupplierForTesting(() -> tempDir.toFile());
        ModelConfigData original = new ModelConfigData();
        original.vrEyeOffsetY = 0.1f;
        original.hiddenMaterials.add(4);
        ModelConfigManager.saveConfig("preview", original);
        String before = Files.readString(ModelConfigManager.getConfigFile("preview").toPath());
        ModelConfigData edited = original.copy();
        edited.vrEyeOffsetY = 0.4f;
        edited.heldItemRotationX = 90.0f;

        ModelConfigManager.previewConfig("preview", edited);
        edited.vrEyeOffsetY = -0.4f;
        assertEquals(0.4f, ModelConfigManager.getLiveConfig("preview").vrEyeOffsetY);
        assertEquals(90.0f, ModelConfigManager.getLiveConfig("preview").heldItemRotationX);
        assertEquals(before, Files.readString(ModelConfigManager.getConfigFile("preview").toPath()));

        ModelConfigManager.previewConfig("preview", original);
        assertEquals(0.1f, ModelConfigManager.getLiveConfig("preview").vrEyeOffsetY);
        assertEquals(0.0f, ModelConfigManager.getLiveConfig("preview").heldItemRotationX);
        assertEquals(original.hiddenMaterials, ModelConfigManager.getLiveConfig("preview").hiddenMaterials);
        assertEquals(before, Files.readString(ModelConfigManager.getConfigFile("preview").toPath()));
    }

    @Test
    void shouldNotCreateAConfigFileForAnUnsavedPreview() {
        ModelConfigManager.setConfigRootDirSupplierForTesting(() -> tempDir.toFile());
        ModelConfigData config = new ModelConfigData();
        config.heldItemScale = 1.5f;

        ModelConfigManager.previewConfig("unsaved", config);

        assertFalse(ModelConfigManager.getConfigFile("unsaved").exists());
        assertEquals(1.5f, ModelConfigManager.getLiveConfig("unsaved").heldItemScale);
        ModelConfigManager.invalidate("unsaved");
        assertEquals(ModelConfigData.DEFAULT_HELD_ITEM_SCALE, ModelConfigManager.getLiveConfig("unsaved").heldItemScale);
    }
}
