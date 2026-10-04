package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.compat.vr.hand.SteamVrHandProvider;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.config.ModelConfigData.ThumbCalibration;
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
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Per-model hand input and thumb calibration, with an unsaved and reversible live preview. */
public final class VrHandSettingsScreen extends Screen {
    private static final ModelSettingsApplicationService SERVICE = ModelSelectorServices.modelSettings();
    private final String modelName;
    private final Screen parentScreen;
    private final ModelConfigData original;
    private final ModelConfigData config;
    private boolean committed, restored;
    private int page, selectedHand;
    private int panelX, panelY, panelWidth, panelHeight;

    public VrHandSettingsScreen(String modelName, Screen parentScreen) {
        super(text("title", "手势追踪设置"));
        this.modelName = modelName;
        this.parentScreen = parentScreen;
        original = SERVICE.loadEditableConfig(modelName);
        config = original.copy();
    }

    @Override protected void init() {
        panelWidth = Math.min(width - 12, 640);
        panelHeight = Math.min(height - 12, 350);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        int inside = panelWidth - 16, column = (inside - 8) / 2;
        Button overview = addRenderableWidget(Button.builder(text("tracking_tab", "追踪开关与状态"), b -> switchPage(0))
                .bounds(panelX + 8, panelY + 24, column, 24).build());
        Button thumb = addRenderableWidget(Button.builder(text("thumb_tab", "拇指校正"), b -> switchPage(1))
                .bounds(panelX + 16 + column, panelY + 24, column, 24).build());
        overview.active = page != 0;
        thumb.active = page != 1;
        if (page == 0) initTracking(inside, column); else initThumbs(inside, column);

        int buttonWidth = (inside - 12) / 3, bottom = panelY + panelHeight - 30;
        addRenderableWidget(Button.builder(text("save", "保存并返回"), b -> saveAndClose())
                .bounds(panelX + 8, bottom, buttonWidth, 24).build());
        addRenderableWidget(Button.builder(text("reset", "重置当前页"), b -> resetPage())
                .bounds(panelX + 14 + buttonWidth, bottom, buttonWidth, 24).build());
        addRenderableWidget(Button.builder(text("cancel", "取消并恢复"), b -> onClose())
                .bounds(panelX + 20 + buttonWidth * 2, bottom, buttonWidth, 24).build());
    }

    private void initTracking(int inside, int column) {
        addRenderableWidget(Button.builder(text("mode_button", "手势追踪：%s", modeLabel()), b -> {
            config.vrFingerTrackingEnabled = !config.vrFingerTrackingEnabled;
            preview(); rebuildWidgets();
        }).bounds(panelX + 8, panelY + 78, inside, 30).build());
        addRenderableWidget(Button.builder(text("left_toggle", "左手追踪：%s", enabledLabel(config.vrLeftHandTrackingEnabled)), b -> {
            config.vrLeftHandTrackingEnabled = !config.vrLeftHandTrackingEnabled;
            preview(); rebuildWidgets();
        }).bounds(panelX + 8, panelY + 116, column, 28).build());
        addRenderableWidget(Button.builder(text("right_toggle", "右手追踪：%s", enabledLabel(config.vrRightHandTrackingEnabled)), b -> {
            config.vrRightHandTrackingEnabled = !config.vrRightHandTrackingEnabled;
            preview(); rebuildWidgets();
        }).bounds(panelX + 16 + column, panelY + 116, column, 28).build());
    }

    private void initThumbs(int inside, int column) {
        int handWidth = (inside - 12) / 3;
        String[] handKeys = {"left", "right", "both"};
        String[] handFallbacks = {"左手", "右手", "双手"};
        for (int i = 0; i < 3; i++) {
            int hand = i;
            Button button = addRenderableWidget(Button.builder(text(handKeys[i], handFallbacks[i]), b -> {
                selectedHand = hand; rebuildWidgets();
            }).bounds(panelX + 8 + i * (handWidth + 6), panelY + 80, handWidth, 22).build());
            button.active = selectedHand != hand;
            if (i == 2) button.setTooltip(Tooltip.create(text("both_hint", "显示左手数值；调整某一项时，该项同时应用于双手。")));
        }
        List<Field> fields = List.of(
                new Field("curl_axis", "弯曲方向", -180, 180, 1, "%+.0f°", t -> t.curlAxisOffsetDeg, (t,v) -> t.curlAxisOffsetDeg = v),
                new Field("opposition", "向掌心收拢", -90, 90, 1, "%+.0f°", t -> t.oppositionOffsetDeg, (t,v) -> t.oppositionOffsetDeg = v),
                new Field("base_offset", "根部弯曲偏移", -90, 90, 1, "%+.0f°", t -> t.baseCurlOffsetDeg, (t,v) -> t.baseCurlOffsetDeg = v),
                new Field("base_gain", "根节弯曲倍率", 0, 3, .05f, "%.2f×", t -> t.baseCurlScale, (t,v) -> t.baseCurlScale = v),
                new Field("middle_gain", "中节弯曲倍率", 0, 3, .05f, "%.2f×", t -> t.middleCurlScale, (t,v) -> t.middleCurlScale = v),
                new Field("tip_gain", "末节弯曲倍率", 0, 3, .05f, "%.2f×", t -> t.tipCurlScale, (t,v) -> t.tipCurlScale = v));
        int rowHeight = Math.max(20, Math.min(32, (panelHeight - 158) / 3));
        for (int i = 0; i < fields.size(); i++) {
            int x = panelX + 8 + (i % 2) * (column + 8);
            int y = panelY + 110 + (i / 2) * (rowHeight + 6);
            addField(fields.get(i), x, y, column, rowHeight);
        }
    }

    private void addField(Field field, int x, int y, int width, int height) {
        int step = Math.min(24, height);
        FieldSlider slider = new FieldSlider(x + step + 2, y, width - 2 * step - 4, height, field);
        addRenderableWidget(Button.builder(Component.literal("−"), b -> slider.adjust(-field.step))
                .bounds(x, y, step, height).build());
        addRenderableWidget(slider);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> slider.adjust(field.step))
                .bounds(x + width - step, y, step, height).build());
    }

    private ThumbCalibration displayedThumb() { return selectedHand == 1 ? config.vrRightThumb : config.vrLeftThumb; }

    private void writeField(Field field, float value) {
        if (selectedHand != 1) field.write.accept(config.vrLeftThumb, value);
        if (selectedHand != 0) field.write.accept(config.vrRightThumb, value);
        preview();
    }

    private void resetPage() {
        if (page == 0) {
            config.vrFingerTrackingEnabled = true;
            config.vrLeftHandTrackingEnabled = true;
            config.vrRightHandTrackingEnabled = true;
        } else {
            if (selectedHand != 1) config.vrLeftThumb = new ThumbCalibration();
            if (selectedHand != 0) config.vrRightThumb = new ThumbCalibration();
        }
        preview(); rebuildWidgets();
    }

    private void switchPage(int nextPage) { page = nextPage; rebuildWidgets(); }
    private void preview() { SERVICE.preview(modelName, config); }
    private void saveAndClose() { SERVICE.save(modelName, config); committed = true; onClose(); }
    private void restorePreview() {
        if (!committed && !restored) { SERVICE.preview(modelName, original); restored = true; }
    }
    @Override public void onClose() { restorePreview(); Minecraft.getInstance().setScreen(parentScreen); }
    @Override public void removed() { restorePreview(); super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xDC101A24);
        graphics.drawCenteredString(font, font.plainSubstrByWidth(title.getString() + " · " + modelName, panelWidth - 16),
                width / 2, panelY + 8, 0xFFB4DAFF);
        Component hint = page == 0
                ? text("tracking_hint", "只控制手指动作；关闭后仍保留头部与手臂跟随。参数按模型保存。")
                : text("thumb_hint", "先调弯曲方向，再调向掌心收拢与根部偏移，最后调倍率。不修改模型文件。 ");
        var lines = font.split(hint, panelWidth - 20);
        for (int i = 0; i < Math.min(2, lines.size()); i++) graphics.drawString(font, lines.get(i), panelX + 10, panelY + 54 + i * 10, 0xFFCCCCCC);
        if (page == 0) {
            int column = (panelWidth - 24) / 2;
            drawStatus(graphics, true, panelX + 8, panelY + 152, column);
            drawStatus(graphics, false, panelX + 16 + column, panelY + 152, column);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawStatus(GuiGraphics graphics, boolean left, int x, int y, int width) {
        boolean enabled = config.vrFingerTrackingEnabled && (left ? config.vrLeftHandTrackingEnabled : config.vrRightHandTrackingEnabled);
        SteamVrHandProvider.RawHand sample = enabled ? SteamVrHandProvider.readHand(left ? 1 : 0) : null;
        Component status;
        if (!enabled) status = text("status_disabled", "已关闭手势追踪");
        else if (sample == null) status = text("status_waiting", "未收到手指数据：保留模型原动作");
        else if (sample.bones() != null && sample.referenceOpenHand() != null) status = text("status_skeleton", "骨骼动作已接入");
        else status = text("status_summary", "手指概要动作已接入");
        var lines = font.split(status, width);
        for (int i = 0; i < Math.min(3, lines.size()); i++) graphics.drawString(font, lines.get(i), x, y + i * 10,
                !enabled ? 0xFFAAAAAA : sample == null ? 0xFFFFCC88 : 0xFF99EEBB);
    }

    private Component modeLabel() { return config.vrFingerTrackingEnabled ? text("auto", "自动") : text("off", "关闭"); }
    private static Component enabledLabel(boolean enabled) { return enabled ? text("on", "开启") : text("off", "关闭"); }
    private static Component text(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("gui.mmdskin.vr_hands." + key, fallback, args);
    }
    private record Field(String key, String fallback, float min, float max, float step, String format,
                         Function<ThumbCalibration, Float> read, BiConsumer<ThumbCalibration, Float> write) {}

    private final class FieldSlider extends AbstractSliderButton {
        private final Field field;
        FieldSlider(int x, int y, int width, int height, Field field) {
            super(x, y, width, height, Component.empty(), (field.read.apply(displayedThumb()) - field.min) / (field.max - field.min));
            this.field = field;
            setTooltip(Tooltip.create(text(field.key + "_hint", switch (field.key) {
                case "curl_axis" -> "旋转拇指弯曲的平面，使拇指朝掌心弯曲。左右手可分别调整。";
                case "opposition" -> "正值使拇指向食指和掌心收拢；负值使拇指张开。";
                case "base_offset" -> "正值增加根部向掌内弯曲的角度；先小幅调整。";
                default -> "放大或减小所选拇指关节的跟随幅度。1.00× 保持原幅度。";
            })));
            updateMessage();
        }
        void adjust(float delta) {
            value = Math.clamp((field.read.apply(displayedThumb()) + delta - field.min) / (field.max - field.min), 0, 1);
            applyValue(); updateMessage();
        }
        @Override protected void updateMessage() {
            setMessage(Component.literal(text(field.key, field.fallback).getString() + ": "
                    + String.format(Locale.ROOT, field.format, field.read.apply(displayedThumb()))));
        }
        @Override protected void applyValue() {
            float raw = field.min + (float)value * (field.max - field.min);
            writeField(field, Math.clamp(Math.round(raw / field.step) * field.step, field.min, field.max));
        }
    }
}
