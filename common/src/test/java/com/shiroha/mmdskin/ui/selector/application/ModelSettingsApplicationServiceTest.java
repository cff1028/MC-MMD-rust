package com.shiroha.mmdskin.ui.selector.application;

import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.ui.selector.port.ModelSettingsGateway;
import com.shiroha.mmdskin.ui.selector.port.ModelSettingsRuntimeGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSettingsApplicationServiceTest {

    @Test
    void shouldReturnEditableCopy() {
        FakeSettingsGateway gateway = new FakeSettingsGateway();
        gateway.stored.eyeTrackingEnabled = false;
        ModelSettingsApplicationService service = new ModelSettingsApplicationService(gateway, (modelName, config) -> {
        });

        ModelConfigData editable = service.loadEditableConfig("alice");
        editable.eyeTrackingEnabled = true;

        assertTrue(editable.eyeTrackingEnabled);
        assertEquals(false, gateway.stored.eyeTrackingEnabled);
    }

    @Test
    void shouldToggleQuickSlotBinding() {
        FakeSettingsGateway gateway = new FakeSettingsGateway();
        ModelSettingsApplicationService service = new ModelSettingsApplicationService(gateway, (modelName, config) -> {
        });

        service.toggleQuickSlot("alice", 1);
        assertEquals("alice", gateway.quickSlots[1]);

        service.toggleQuickSlot("alice", 1);
        assertNull(gateway.quickSlots[1]);
    }

    @Test
    void shouldSaveAndApplyRuntime() {
        FakeSettingsGateway gateway = new FakeSettingsGateway();
        FakeRuntimeGateway runtimeGateway = new FakeRuntimeGateway();
        ModelSettingsApplicationService service = new ModelSettingsApplicationService(gateway, runtimeGateway);
        ModelConfigData config = new ModelConfigData();
        config.eyeMaxAngle = 0.6f;

        service.save("alice", config);

        assertEquals(0.6f, gateway.saved.eyeMaxAngle);
        assertEquals("alice", runtimeGateway.lastModelName);
    }

    @Test
    void shouldPreviewAndRestoreThroughRuntimeWithoutSaving() {
        FakeSettingsGateway gateway = new FakeSettingsGateway();
        FakeRuntimeGateway runtimeGateway = new FakeRuntimeGateway();
        ModelSettingsApplicationService service = new ModelSettingsApplicationService(gateway, runtimeGateway);
        ModelConfigData original = service.loadEditableConfig("alice");
        ModelConfigData edited = original.copy();
        edited.vrEyeOffsetZ = 0.3f;
        edited.heldItemRotationY = 40.0f;

        service.preview("alice", edited);
        assertEquals(0.3f, gateway.previewed.vrEyeOffsetZ);
        assertEquals(40.0f, gateway.previewed.heldItemRotationY);
        assertEquals("alice", runtimeGateway.lastModelName);
        assertNull(gateway.saved);

        service.preview("alice", original);
        assertEquals(0.0f, gateway.previewed.vrEyeOffsetZ);
        assertEquals(0.0f, gateway.previewed.heldItemRotationY);
        assertNull(gateway.saved);
    }

    private static final class FakeSettingsGateway implements ModelSettingsGateway {
        private final String[] quickSlots = new String[4];
        private final ModelConfigData stored = new ModelConfigData();
        private ModelConfigData saved;
        private ModelConfigData previewed;

        @Override
        public ModelConfigData loadConfig(String modelName) {
            return stored;
        }

        @Override
        public void saveConfig(String modelName, ModelConfigData config) {
            saved = config.copy();
        }

        @Override
        public void previewConfig(String modelName, ModelConfigData config) {
            previewed = config.copy();
        }

        @Override
        public String getQuickSlotModel(int slot) {
            return quickSlots[slot];
        }

        @Override
        public void setQuickSlotModel(int slot, String modelName) {
            quickSlots[slot] = modelName;
        }
    }

    private static final class FakeRuntimeGateway implements ModelSettingsRuntimeGateway {
        private String lastModelName;

        @Override
        public void applyConfigIfSelected(String modelName, ModelConfigData config) {
            lastModelName = modelName;
        }
    }
}
