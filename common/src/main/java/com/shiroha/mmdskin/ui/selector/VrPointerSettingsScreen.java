package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.config.VrPointerConfigData;
import com.shiroha.mmdskin.config.VrPointerConfigData.PressBinding;
import com.shiroha.mmdskin.config.VrPointerConfigData.CrosshairMode;
import com.shiroha.mmdskin.config.VrPointerConfigData.Visibility;
import com.shiroha.mmdskin.config.VrPointerConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Global pointer editor. Changes are visible immediately but stay reversible until explicitly saved. */
public final class VrPointerSettingsScreen extends Screen {
    private final Screen parentScreen;
    private final VrPointerConfigData original = VrPointerConfigManager.getConfig();
    private VrPointerConfigData config = original.copy();
    private final List<ColorField> colors = new ArrayList<>();
    private final List<EditBox> colorBoxes = new ArrayList<>();
    private boolean committed, restored, saveFailed;
    private int page, panelX, panelY, panelWidth, panelHeight, column, rowPitch;
    private Button saveButton;

    public VrPointerSettingsScreen(Screen parentScreen) {
        super(text("title"));
        this.parentScreen = parentScreen;
    }

    @Override protected void init() {
        colors.clear(); colorBoxes.clear();
        panelWidth = Math.min(width - 12, 640);
        panelHeight = Math.min(height - 12, 330);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        column = (panelWidth - 24) / 2;
        rowPitch = Math.min(58, (panelHeight - 114) / 3);
        int tabWidth = (panelWidth - 40) / 5;
        String[] pages = {"basic_tab", "colors_tab", "size_tab", "animation_tab", "interaction_tab"};
        for (int i = 0; i < pages.length; i++) {
            int target = i;
            Button tab = addRenderableWidget(Button.builder(text(pages[i]), b -> { page = target; rebuildWidgets(); })
                    .bounds(panelX + 8 + i * (tabWidth + 6), panelY + 24, tabWidth, 24).build());
            tab.active = page != i;
        }
        switch (page) {
            case 0 -> initBasic();
            case 1 -> initColors();
            case 2 -> initSizes();
            case 3 -> initAnimation();
            default -> initInteraction();
        }
        int footerWidth = (panelWidth - 28) / 3, bottom = panelY + panelHeight - 30;
        saveButton = addRenderableWidget(Button.builder(text("save"), b -> saveAndClose())
                .bounds(panelX + 8, bottom, footerWidth, 24).build());
        addRenderableWidget(Button.builder(text("reset"), b -> resetPage())
                .bounds(panelX + 14 + footerWidth, bottom, footerWidth, 24).build());
        addRenderableWidget(Button.builder(text("cancel"), b -> onClose())
                .bounds(panelX + 20 + 2 * footerWidth, bottom, footerWidth, 24).build());
        validateColors();
    }

    private void initBasic() {
        button(0, "visibility", text("visibility_" + config.visibility.name().toLowerCase(Locale.ROOT)), () -> {
            config.visibility = Visibility.values()[(config.visibility.ordinal() + 1) % Visibility.values().length];
        }).setTooltip(Tooltip.create(text("visibility_hint")));
        button(1, "binding", text("binding_" + config.pressBinding.name().toLowerCase(Locale.ROOT)), () -> {
            config.pressBinding = PressBinding.values()[(config.pressBinding.ordinal() + 1) % PressBinding.values().length];
        }).setTooltip(Tooltip.create(text("binding_hint")));
        button(2, "left", onOff(config.leftEnabled), () -> config.leftEnabled = !config.leftEnabled);
        button(3, "right", onOff(config.rightEnabled), () -> config.rightEnabled = !config.rightEnabled);
        button(4, "dot_enabled", onOff(config.dotEnabled), () -> config.dotEnabled = !config.dotEnabled);
        button(5, "hide_crosshair", text("hide_crosshair_" + config.hideCrosshairMode.name().toLowerCase(Locale.ROOT)), () -> {
            config.hideCrosshairMode = CrosshairMode.values()[(config.hideCrosshairMode.ordinal() + 1) % CrosshairMode.values().length];
        })
                .setTooltip(Tooltip.create(text("hide_crosshair_hint")));
    }

    private void initInteraction() {
        button(0, "left_trigger", onOff(config.leftTriggerInteraction), () -> config.leftTriggerInteraction = !config.leftTriggerInteraction)
                .setTooltip(Tooltip.create(text("left_trigger_hint")));
    }

    private void initColors() {
        color(0, "ray_color", () -> config.rayColor, value -> config.rayColor = value);
        color(1, "dot_color", () -> config.dotColor, value -> config.dotColor = value);
        color(2, "pressed_ray_color", () -> config.pressedRayColor, value -> config.pressedRayColor = value);
        color(3, "pressed_dot_color", () -> config.pressedDotColor, value -> config.pressedDotColor = value);
        field(4, new Field("ray_alpha", 0, 1, .01f, 100, "%.0f%%", c -> c.rayAlpha, (c,v) -> c.rayAlpha = v));
        field(5, new Field("dot_alpha", 0, 1, .01f, 100, "%.0f%%", c -> c.dotAlpha, (c,v) -> c.dotAlpha = v));
    }

    private void initSizes() {
        field(0, new Field("ray_width", .0005f, .02f, .0001f, 1000, "%.1f mm", c -> c.rayWidth, (c,v) -> c.rayWidth = v));
        field(1, new Field("distance", .25f, 32, .25f, 1, "%.2f m", c -> c.maxDistance, (c,v) -> c.maxDistance = v));
        field(2, new Field("dot_radius", .0005f, .025f, .0001f, 100, "%.2f%%", c -> c.dotRadiusView, (c,v) -> c.dotRadiusView = v));
        field(3, new Field("pressed_radius", .0005f, .025f, .0001f, 100, "%.2f%%", c -> c.pressedDotRadiusView, (c,v) -> c.pressedDotRadiusView = v));
    }

    private void initAnimation() {
        button(0, "color_animation", onOff(config.colorAnimationEnabled), () -> config.colorAnimationEnabled = !config.colorAnimationEnabled);
        button(1, "radius_animation", onOff(config.radiusAnimationEnabled), () -> config.radiusAnimationEnabled = !config.radiusAnimationEnabled);
        field(2, new Field("color_duration", 0, 2000, 10, 1, "%.0f ms", c -> c.colorDurationMs, (c,v) -> c.colorDurationMs = v));
        field(3, new Field("radius_duration", 0, 2000, 10, 1, "%.0f ms", c -> c.radiusDurationMs, (c,v) -> c.radiusDurationMs = v));
        field(4, new Field("fade_in", 0, 2000, 10, 1, "%.0f ms", c -> c.fadeInMs, (c,v) -> c.fadeInMs = v));
        field(5, new Field("fade_out", 0, 2000, 10, 1, "%.0f ms", c -> c.fadeOutMs, (c,v) -> c.fadeOutMs = v));
    }

    private int cellX(int index) { return panelX + 8 + (index % 2) * (column + 8); }
    private int cellY(int index) { return panelY + 80 + (index / 2) * rowPitch; }
    private Button button(int index, String key, Component value, Runnable change) {
        return addRenderableWidget(Button.builder(text(key, value), b -> { change.run(); preview(); rebuildWidgets(); })
                .bounds(cellX(index), cellY(index) + 8, column, 24).build());
    }

    private void color(int index, String key, IntSupplier read, IntConsumer write) {
        int x = cellX(index), y = cellY(index);
        ColorField field = new ColorField(key, x, y, read);
        colors.add(field);
        EditBox box = new EditBox(font, x, y + 12, column - 30, 20, text(key));
        box.setMaxLength(7);
        box.setFilter(value -> value.matches("#?[0-9a-fA-F]{0,6}"));
        box.setValue(String.format(Locale.ROOT, "#%06X", read.getAsInt()));
        box.setTooltip(Tooltip.create(text("hex_hint")));
        box.setResponder(value -> {
            if (isHexColor(value)) { write.accept(Integer.parseInt(value.replace("#", ""), 16)); preview(); }
            box.setTextColor(isHexColor(value) ? 0xFFFFFF : 0xFF7777);
            validateColors();
        });
        colorBoxes.add(box);
        addRenderableWidget(box);
    }

    private static boolean isHexColor(String value) { return value.matches("#?[0-9a-fA-F]{6}"); }
    private void validateColors() {
        if (saveButton != null) saveButton.active = colorBoxes.stream().allMatch(box -> isHexColor(box.getValue()));
    }

    private void field(int index, Field field) {
        int x = cellX(index), y = cellY(index) + 8, step = 22;
        FieldSlider slider = new FieldSlider(x + step + 2, y, column - 2 * step - 4, 24, field);
        addRenderableWidget(Button.builder(Component.literal("−"), b -> slider.adjust(-field.step))
                .bounds(x, y, step, 24).build());
        addRenderableWidget(slider);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> slider.adjust(field.step))
                .bounds(x + column - step, y, step, 24).build());
    }

    private void resetPage() {
        VrPointerConfigData defaults = new VrPointerConfigData();
        switch (page) {
            case 0 -> {
                config.visibility = defaults.visibility;
                config.pressBinding = defaults.pressBinding;
                config.leftEnabled = defaults.leftEnabled;
                config.rightEnabled = defaults.rightEnabled;
                config.dotEnabled = defaults.dotEnabled;
                config.hideCrosshairMode = defaults.hideCrosshairMode;
            }
            case 1 -> {
                config.rayColor = defaults.rayColor; config.dotColor = defaults.dotColor;
                config.pressedRayColor = defaults.pressedRayColor; config.pressedDotColor = defaults.pressedDotColor;
                config.rayAlpha = defaults.rayAlpha; config.dotAlpha = defaults.dotAlpha;
            }
            case 2 -> {
                config.rayWidth = defaults.rayWidth; config.maxDistance = defaults.maxDistance;
                config.dotRadiusView = defaults.dotRadiusView; config.pressedDotRadiusView = defaults.pressedDotRadiusView;
            }
            case 4 -> config.leftTriggerInteraction = defaults.leftTriggerInteraction;
            default -> {
                config.colorAnimationEnabled = defaults.colorAnimationEnabled;
                config.radiusAnimationEnabled = defaults.radiusAnimationEnabled;
                config.colorDurationMs = defaults.colorDurationMs; config.radiusDurationMs = defaults.radiusDurationMs;
                config.fadeInMs = defaults.fadeInMs; config.fadeOutMs = defaults.fadeOutMs;
            }
        }
        preview(); rebuildWidgets();
    }

    private void preview() { VrPointerConfigManager.preview(config); saveFailed = false; }
    private void saveAndClose() {
        if (colorBoxes.stream().anyMatch(box -> !isHexColor(box.getValue()))) return;
        if (!VrPointerConfigManager.save(config)) { saveFailed = true; return; }
        committed = true; onClose();
    }
    private void restorePreview() {
        if (!committed && !restored) { VrPointerConfigManager.preview(original); restored = true; }
    }
    @Override public void onClose() { restorePreview(); Minecraft.getInstance().setScreen(parentScreen); }
    @Override public void removed() { restorePreview(); super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xDC101A24);
        graphics.drawCenteredString(font, title, width / 2, panelY + 8, 0xFFB4DAFF);
        String hint = saveFailed ? "save_error" : switch (page) {
            case 0 -> "basic_hint";
            case 1 -> "colors_hint";
            case 2 -> "size_hint";
            case 3 -> "animation_hint";
            default -> "interaction_hint";
        };
        var lines = font.split(text(hint), panelWidth - 20);
        for (int i = 0; i < Math.min(2, lines.size()); i++) graphics.drawString(font, lines.get(i), panelX + 10,
                panelY + 54 + i * 10, saveFailed ? 0xFFFF9999 : 0xFFCCCCCC);
        for (ColorField color : colors) {
            graphics.drawString(font, text(color.key), color.x, color.y, 0xFFFFFFFF);
            graphics.fill(color.x + column - 24, color.y + 12, color.x + column, color.y + 32, 0xFF000000 | color.read.getAsInt());
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static Component text(String key, Object... args) { return Component.translatable("gui.mmdskin.vr_pointer." + key, args); }
    private static Component onOff(boolean enabled) { return text(enabled ? "on" : "off"); }
    private record ColorField(String key, int x, int y, IntSupplier read) {}
    private record Field(String key, float min, float max, float step, float displayScale, String format,
                         Function<VrPointerConfigData, Float> read, BiConsumer<VrPointerConfigData, Float> write) {}

    private final class FieldSlider extends AbstractSliderButton {
        private final Field field;
        private FieldSlider(int x, int y, int width, int height, Field field) {
            super(x, y, width, height, Component.empty(), (field.read.apply(config) - field.min) / (field.max - field.min));
            this.field = field;
            updateMessage();
            setTooltip(Tooltip.create(text(field.key)));
        }
        void adjust(float delta) {
            value = Math.clamp((field.read.apply(config) + delta - field.min) / (field.max - field.min), 0, 1);
            applyValue(); updateMessage();
        }
        @Override protected void updateMessage() {
            setMessage(Component.literal(text(field.key).getString() + ": "
                    + String.format(Locale.ROOT, field.format, field.read.apply(config) * field.displayScale)));
        }
        @Override protected void applyValue() {
            float raw = field.min + (float)value * (field.max - field.min);
            field.write.accept(config, Math.clamp(Math.round(raw / field.step) * field.step, field.min, field.max));
            preview();
        }
    }
}
