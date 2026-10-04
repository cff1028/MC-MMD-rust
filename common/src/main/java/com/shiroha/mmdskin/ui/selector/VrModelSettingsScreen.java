package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.compat.vr.VrCalibrationController;
import com.shiroha.mmdskin.compat.vr.VrCalibrationMath;
import com.shiroha.mmdskin.ui.selector.application.ModelSettingsApplicationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Controller-pointer friendly model calibration with a live, reversible preview. */
public final class VrModelSettingsScreen extends Screen {
    private static final ModelSettingsApplicationService SERVICE = ModelSelectorServices.modelSettings();
    private final String modelName;
    private final Screen parentScreen;
    private final ModelConfigData original;
    private final ModelConfigData config;
    private int page;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private boolean committed;
    private boolean restored;
    private boolean calibrationSuspended;
    private VrCalibrationMath.ModelMetrics metrics;
    private Component calibrationError;

    public VrModelSettingsScreen(String modelName, Screen parentScreen) {
        super(text("title", "VR 模型校准"));
        this.modelName = modelName;
        this.parentScreen = parentScreen;
        this.original = SERVICE.loadEditableConfig(modelName);
        this.config = original.copy();
    }

    @Override
    protected void init() {
        metrics = VrCalibrationController.modelMetrics(modelName);
        panelWidth = Math.min(width - 12, 620);
        panelHeight = Math.min(height - 12, 340);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        int insideWidth = panelWidth - 16;
        int tabWidth = (insideWidth - 6) / 2;
        Button viewTab = addRenderableWidget(Button.builder(text("view_tab", "视角与模型"),
                button -> switchPage(0)).bounds(panelX + 8, panelY + 24, tabWidth, 24).build());
        Button itemTab = addRenderableWidget(Button.builder(text("item_tab", "手持物品"),
                button -> switchPage(1)).bounds(panelX + 14 + tabWidth, panelY + 24, tabWidth, 24).build());
        viewTab.active = page != 0;
        itemTab.active = page != 1;

        List<Field> fields = page == 0 ? modelFields() : itemFields();
        int fieldRows = (fields.size() + 1) / 2;
        int rows = fieldRows + (page == 0 ? 1 : 0);
        int columnWidth = (insideWidth - 10) / 2;
        int rowHeight = Math.max(20, Math.min(34, (panelHeight - 110 - (rows - 1) * 6) / rows));
        for (int i = 0; i < fields.size(); i++) {
            int x = panelX + 8 + (i % 2) * (columnWidth + 10);
            int y = panelY + 70 + (i / 2) * (rowHeight + 6);
            addField(fields.get(i), x, y, columnWidth, rowHeight);
        }
        if (page == 0) {
            addRenderableWidget(Button.builder(text("calibrate", "T 字站姿：校准身高与臂长"), button -> startCalibration())
                    .bounds(panelX + 8, panelY + 70 + fieldRows * (rowHeight + 6), insideWidth, rowHeight).build());
        }

        int buttonWidth = (insideWidth - 12) / 3;
        int buttonY = panelY + panelHeight - 30;
        addRenderableWidget(Button.builder(text("save", "保存并返回"), button -> saveAndClose())
                .bounds(panelX + 8, buttonY, buttonWidth, 24).build());
        addRenderableWidget(Button.builder(text("reset", "重置当前页"), button -> resetPage())
                .bounds(panelX + 14 + buttonWidth, buttonY, buttonWidth, 24).build());
        addRenderableWidget(Button.builder(text("cancel", "取消并恢复"), button -> onClose())
                .bounds(panelX + 20 + buttonWidth * 2, buttonY, buttonWidth, 24).build());
    }

    private void addField(Field field, int x, int y, int width, int height) {
        int stepWidth = Math.min(26, height);
        FieldSlider slider = new FieldSlider(x + stepWidth + 3, y, width - stepWidth * 2 - 6, height, field);
        addRenderableWidget(Button.builder(Component.literal("−"), button -> slider.adjust(-field.step()))
                .bounds(x, y, stepWidth, height).build());
        addRenderableWidget(slider);
        addRenderableWidget(Button.builder(Component.literal("+"), button -> slider.adjust(field.step()))
                .bounds(x + width - stepWidth, y, stepWidth, height).build());
    }

    private List<Field> modelFields() {
        Field height = metrics == null
                ? new Field("model_scale", "模型缩放", 0.5f, 2.0f, 0.01f, "%.2f×", () -> config.modelScale, this::setModelScale)
                : new Field("eye_height", "身高（眼高）", metrics.baseEyeHeightMetres() * ModelConfigData.MIN_MODEL_SCALE,
                    metrics.baseEyeHeightMetres() * ModelConfigData.MAX_MODEL_SCALE, 0.01f, "%.2f m",
                    () -> config.modelScale * metrics.baseEyeHeightMetres(), value -> setModelScale(value / metrics.baseEyeHeightMetres()));
        return List.of(
                height,
                new Field("arm_length", "臂长校准", ModelConfigData.MIN_VR_ARM_LENGTH_SCALE,
                        ModelConfigData.MAX_VR_ARM_LENGTH_SCALE, 0.01f, "%.2f×", () -> config.vrArmLengthScale, value -> config.vrArmLengthScale = value),
                new Field("eye_x", "眼位 X", -0.5f, 0.5f, 0.01f, "%+.2f m", () -> config.vrEyeOffsetX, value -> config.vrEyeOffsetX = value),
                new Field("eye_y", "眼位 Y", -0.5f, 0.5f, 0.01f, "%+.2f m", () -> config.vrEyeOffsetY, value -> config.vrEyeOffsetY = value),
                new Field("eye_z", "眼位 Z", -0.5f, 0.5f, 0.01f, "%+.2f m", () -> config.vrEyeOffsetZ, value -> config.vrEyeOffsetZ = value),
                new Field("arm_ik", "手臂跟随", 0.0f, 1.0f, 0.05f, "%.2f", () -> config.vrArmIkStrength, value -> config.vrArmIkStrength = value)
        );
    }

    private void setModelScale(float value) {
        // Preserve the measured user's reach when manually adjusting avatar height.
        config.vrArmLengthScale = Math.clamp(config.vrArmLengthScale * config.modelScale / value,
                ModelConfigData.MIN_VR_ARM_LENGTH_SCALE, ModelConfigData.MAX_VR_ARM_LENGTH_SCALE);
        config.modelScale = value;
    }

    private List<Field> itemFields() {
        return List.of(
                new Field("item_scale", "物品缩放", 0.25f, 2.0f, 0.01f, "%.2f×", () -> config.heldItemScale, value -> config.heldItemScale = value),
                new Field("item_x", "偏移 X", -1.0f, 1.0f, 0.01f, "%+.2f", () -> config.heldItemOffsetX, value -> config.heldItemOffsetX = value),
                new Field("item_y", "偏移 Y", -1.0f, 1.0f, 0.01f, "%+.2f", () -> config.heldItemOffsetY, value -> config.heldItemOffsetY = value),
                new Field("item_z", "偏移 Z", -1.0f, 1.0f, 0.01f, "%+.2f", () -> config.heldItemOffsetZ, value -> config.heldItemOffsetZ = value),
                new Field("item_pitch", "俯仰 X", -180.0f, 180.0f, 1.0f, "%+.0f°", () -> config.heldItemRotationX, value -> config.heldItemRotationX = value),
                new Field("item_yaw", "偏航 Y", -180.0f, 180.0f, 1.0f, "%+.0f°", () -> config.heldItemRotationY, value -> config.heldItemRotationY = value),
                new Field("item_roll", "翻滚 Z", -180.0f, 180.0f, 1.0f, "%+.0f°", () -> config.heldItemRotationZ, value -> config.heldItemRotationZ = value)
        );
    }

    private void switchPage(int nextPage) {
        page = nextPage;
        rebuildWidgets();
    }

    private void resetPage() {
        if (page == 0) {
            config.modelScale = ModelConfigData.DEFAULT_MODEL_SCALE;
            config.vrArmIkStrength = 1.0f;
            config.vrArmLengthScale = 1.0f;
            config.vrEyeOffsetX = config.vrEyeOffsetY = config.vrEyeOffsetZ = 0.0f;
        } else {
            config.heldItemScale = ModelConfigData.DEFAULT_HELD_ITEM_SCALE;
            config.heldItemOffsetX = config.heldItemOffsetY = config.heldItemOffsetZ = 0.0f;
            config.heldItemRotationX = config.heldItemRotationY = config.heldItemRotationZ = 0.0f;
        }
        preview();
        rebuildWidgets();
    }

    private void preview() {
        SERVICE.preview(modelName, config);
    }

    public boolean startCalibration() {
        calibrationError = null;
        calibrationSuspended = true;
        if (!VrCalibrationController.start(modelName, this)) {
            calibrationSuspended = false;
            return false;
        }
        return true;
    }

    public void showCalibrationError(Component message) {
        calibrationError = message;
    }

    /** Called by the in-world capture controller; results remain an unsaved preview. */
    public void finishCalibration(VrCalibrationMath.Result result, boolean returnToSettings) {
        calibrationSuspended = false;
        if (!returnToSettings) {
            restorePreview();
            return;
        }
        if (result != null && result.usable()) {
            config.modelScale = result.modelScale();
            config.vrArmLengthScale = result.armLengthScale();
            preview();
        }
        Minecraft.getInstance().setScreen(this);
    }

    private void saveAndClose() {
        SERVICE.save(modelName, config);
        committed = true;
        onClose();
    }

    private void restorePreview() {
        if (!committed && !restored && !calibrationSuspended) {
            SERVICE.preview(modelName, original);
            restored = true;
        }
    }

    @Override
    public void onClose() {
        restorePreview();
        Minecraft.getInstance().setScreen(parentScreen);
    }

    @Override
    public void removed() {
        // Also restore when Vivecraft closes the GUI or a world disconnect replaces this screen.
        restorePreview();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the live scene and calibration mirror visible around the controls.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xDC101A24);
        String heading = title.getString() + " · " + modelName;
        graphics.drawCenteredString(font, font.plainSubstrByWidth(heading, panelWidth - 20), width / 2, panelY + 8, 0xFFB4DAFF);
        Component hint = page == 0
                ? text("calibration_hint", "臂长校准只映射动作，保留模型比例；T 字校准提示显示在头显前方。")
                : text("item_hint", "左右手共用：物品局部偏移 / 旋转角度。修改即时预览。 ");
        if (calibrationError != null) hint = calibrationError;
        graphics.drawCenteredString(font, font.plainSubstrByWidth(hint.getString(), panelWidth - 16), width / 2, panelY + 54,
                calibrationError == null ? 0xFFCCCCCC : 0xFFFFBB88);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static Component text(String key, String fallback) {
        return Component.translatableWithFallback("gui.mmdskin.vr_settings." + key, fallback);
    }

    private record Field(String key, String fallback, float min, float max, float step, String format,
                         Supplier<Float> read, Consumer<Float> write) {
    }

    private final class FieldSlider extends AbstractSliderButton {
        private final Field field;

        private FieldSlider(int x, int y, int width, int height, Field field) {
            super(x, y, width, height, Component.empty(), (field.read().get() - field.min()) / (field.max() - field.min()));
            this.field = field;
            if (field.key().equals("arm_length")) {
                setTooltip(Tooltip.create(text("arm_length_hint", "估算肩部到手柄的有效臂长，用于动作映射；不拉伸模型骨骼。无模型尺寸时显示校准比例。")));
            }
            updateMessage();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            value = Math.clamp((field.read().get() - field.min()) / (field.max() - field.min()), 0.0, 1.0);
            updateMessage();
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
        }

        private void adjust(float delta) {
            value = Math.clamp((field.read().get() + delta - field.min()) / (field.max() - field.min()), 0.0, 1.0);
            applyValue();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            String display = field.key().equals("arm_length") && metrics != null
                    ? String.format(Locale.ROOT, "%.2f m", metrics.meanArmLengthMetres(config.modelScale) * config.vrArmLengthScale)
                    : String.format(Locale.ROOT, field.format(), field.read().get());
            setMessage(Component.literal(text(field.key(), field.fallback()).getString() + ": " + display));
        }

        @Override
        protected void applyValue() {
            float selected = field.min() + (float) value * (field.max() - field.min());
            float rounded = Math.round(selected / field.step()) * field.step();
            field.write().accept(Math.clamp(rounded, field.min(), field.max()));
            preview();
        }
    }
}
