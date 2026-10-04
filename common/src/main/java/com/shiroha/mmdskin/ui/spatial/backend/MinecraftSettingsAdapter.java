package com.shiroha.mmdskin.ui.spatial.backend;

import com.shiroha.mmdskin.ui.spatial.lumen.model.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.sounds.SoundSource;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Discovers OptionInstances by type, never reflective Minecraft field/method names (Fabric remaps them). */
final class MinecraftSettingsAdapter {
    private final Options options = Minecraft.getInstance().options;
    private final Map<OptionInstance<?>, Runnable> pending = new LinkedHashMap<>();
    private final Map<OptionInstance<?>, Object> preview = new IdentityHashMap<>();

    List<SettingBinding> bindings() {
        List<SettingBinding> result = new ArrayList<>();
        Set<OptionInstance<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Field field : Options.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !OptionInstance.class.isAssignableFrom(field.getType())) continue;
            try { field.setAccessible(true); addSafely(result, seen, (OptionInstance<?>)field.get(options)); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        for (SoundSource sound : SoundSource.values()) addSafely(result, seen, options.getSoundSourceOptionInstance(sound));
        return result;
    }

    private void addSafely(List<SettingBinding> out, Set<OptionInstance<?>> seen, OptionInstance<?> option) {
        try { add(out, seen, option); }
        catch (RuntimeException failure) {
            org.apache.logging.log4j.LogManager.getLogger().warn("[Spatial UI] Native editor retained for unsupported option {}", option, failure);
            String title = option.toString();
            Setting schema = new Setting("mc.native." + out.size(), "Minecraft", "完整设置与资源", title,
                    "此选项使用专用控件，请在完整 Minecraft 设置中调整。", Setting.Kind.ACTION, 0, 1, 1, "", List.of(), "Minecraft:" + title);
            out.add(new SettingBinding(schema, () -> "", ignored -> com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.openNative(
                    new net.minecraft.client.gui.screens.options.OptionsScreen(Minecraft.getInstance().screen, options))));
        }
    }

    private <T> void add(List<SettingBinding> out, Set<OptionInstance<?>> seen, OptionInstance<T> option) {
        if (option == null || !seen.add(option)) return;
        try {
            Component caption = (Component) fieldByType(OptionInstance.class, Component.class).get(option);
            String key = caption.getContents() instanceof TranslatableContents translated ? translated.getKey() : caption.getString();
            String id = "mc." + key;
            Object values = option.values();
            Method fromSlider = Arrays.stream(values.getClass().getMethods()).filter(method -> !Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0] == double.class && method.getReturnType() != void.class).findFirst().orElse(null);
            AbstractWidget widget = option.createButton(options, 0, 0, 200);
            Function<T, Component> formatter = (Function<T, Component>) fieldByType(OptionInstance.class, Function.class).get(option);
            T initial = initialValue(option);
            String description = tooltip(option, caption);
            if (widget instanceof CycleButton<?> cycle) {
                CycleButton.ValueListSupplier<T> list = (CycleButton.ValueListSupplier<T>) fieldByType(CycleButton.class, CycleButton.ValueListSupplier.class).get(cycle);
                List<T> domain = List.copyOf(list.getSelectedList());
                if (domain.isEmpty()) return;
                boolean bool = domain.size() == 2 && domain.stream().allMatch(Boolean.class::isInstance);
                List<String> labels = domain.stream().map(value -> value instanceof Enum<?> e ? e.name() : String.valueOf(value)).toList();
                if (!bool && domain.size() < 2) {
                    Setting schema = new Setting(id, "Minecraft", section(key), caption.getString(), description,
                            Setting.Kind.TEXT, 0, 1, 1, String.valueOf(option.get()), List.of(), key);
                    out.add(new SettingBinding(schema, () -> String.valueOf(option.get()), ignored -> {}, () -> widget.getMessage().getString(), () -> false, () -> {}));
                    return;
                }
                Object defaultValue = bool ? initial : labels.contains(token(initial)) ? token(initial) : labels.getFirst();
                Setting schema = new Setting(id, "Minecraft", section(key), caption.getString(), description,
                        bool ? Setting.Kind.TOGGLE : Setting.Kind.CHOICE, 0, 1, 1, defaultValue, bool ? List.of() : labels, key);
                Supplier<Object> read = () -> {
                    Object current = preview.getOrDefault(option, option.get()); return bool ? current : token(current);
                };
                var write = (java.util.function.Consumer<Object>) next -> {
                    int index = bool ? domain.indexOf(next) : labels.indexOf(next.toString());
                    if (index < 0) throw new IllegalArgumentException("该选项值当前不可用");
                    T typed = domain.get(index); preview.put(option, typed);
                    pending.put(option, () -> {
                        try {
                            // Invoke the native value setter, retaining graphics warning/renderer update semantics.
                            CycleButton<T> fresh = (CycleButton<T>) option.createButton(options, 0, 0, 200);
                            CycleButton.OnValueChange<T> callback = (CycleButton.OnValueChange<T>) fieldByType(CycleButton.class, CycleButton.OnValueChange.class).get(fresh);
                            fresh.setValue(typed); callback.onValueChange(fresh, typed);
                            if (option == options.graphicsMode() && Minecraft.getInstance().getGpuWarnlistManager().isShowingWarning())
                                showGraphicsWarning(option, typed);
                        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                    });
                };
                out.add(new SettingBinding(schema, read, write,
                        () -> valueLabel(caption, formatter.apply((T)preview.getOrDefault(option, option.get()))), () -> widget.active,
                        () -> write.accept(defaultValue)));
            } else if (fromSlider != null && option.get() instanceof Number) {
                fromSlider.setAccessible(true);
                Method toSlider = Arrays.stream(values.getClass().getMethods()).filter(method -> !Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 1 && method.getReturnType() == double.class
                        && method.getParameterTypes()[0] != double.class).findFirst().orElseThrow();
                toSlider.setAccessible(true);
                double initialUnit = ((Number)toSlider.invoke(values, initial)).doubleValue();
                Setting schema = new Setting(id, "Minecraft", section(key), caption.getString(), description,
                        Setting.Kind.SLIDER, 0, 1, .001, Math.clamp(initialUnit, 0, 1), List.of(), key);
                Supplier<Object> read = () -> {
                    try { return ((Number)toSlider.invoke(values, preview.getOrDefault(option, option.get()))).doubleValue(); }
                    catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                };
                java.util.function.Consumer<Object> write = next -> {
                    try {
                        T typed = (T)fromSlider.invoke(values, Math.clamp(((Number)next).doubleValue(), 0, 1));
                        preview.put(option, typed); pending.put(option, () -> option.set(typed));
                    } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                };
                out.add(new SettingBinding(schema, read, write,
                        () -> valueLabel(caption, formatter.apply((T)preview.getOrDefault(option, option.get()))), () -> widget.active,
                        () -> write.accept(initialUnit)));
            } else {
                throw new IllegalStateException("Unsupported Minecraft option domain: " + key + " / " + values.getClass());
            }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    void flush() {
        if (pending.isEmpty()) return;
        int previousMipmaps = options.mipmapLevels().get();
        List<Runnable> work = new ArrayList<>(pending.values());
        pending.clear(); preview.clear();
        for (Runnable action : work) action.run();
        if (previousMipmaps != options.mipmapLevels().get()) {
            Minecraft.getInstance().updateMaxMipLevel(options.mipmapLevels().get());
            Minecraft.getInstance().delayTextureReload();
        }
        options.save(); options.broadcastOptions();
    }
    private <T> void showGraphicsWarning(OptionInstance<T> option, T requested) {
        Minecraft mc = Minecraft.getInstance();
        var parent = mc.screen;
        var warning = mc.getGpuWarnlistManager();
        com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.openNative(new net.minecraft.client.gui.screens.ConfirmScreen(accepted -> {
            if (accepted) {
                warning.dismissWarning(); option.set(requested); mc.levelRenderer.allChanged(); options.save();
            } else warning.dismissWarningAndSkipFabulous();
            mc.setScreen(parent);
        }, Component.translatable("options.graphics.warning.title"),
                Component.literal("Minecraft 检测到当前显卡的华丽画质兼容性警告：\n" + warning.getAllWarnings())));
    }
    private static String token(Object value) { return value instanceof Enum<?> e ? e.name() : String.valueOf(value); }
    private static <T> String tooltip(OptionInstance<T> option, Component caption) {
        try {
            OptionInstance.TooltipSupplier<T> supplier = (OptionInstance.TooltipSupplier<T>)fieldByType(OptionInstance.class, OptionInstance.TooltipSupplier.class).get(option);
            var tooltip = supplier.apply(option.get());
            if (tooltip != null) {
                String text = ((Component)fieldByType(net.minecraft.client.gui.components.Tooltip.class, Component.class).get(tooltip)).getString();
                if (!text.isBlank()) return text;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return "调整" + caption.getString() + "。";
    }
    private static String valueLabel(Component caption, Component value) {
        String full = value.getString(), prefix = caption.getString();
        if (!full.startsWith(prefix) || full.length() == prefix.length()) return full;
        String suffix = full.substring(prefix.length());
        if (suffix.stripLeading().startsWith(":" ) || suffix.stripLeading().startsWith("：")) return suffix.stripLeading().substring(1).stripLeading();
        return full;
    }
    private static Field fieldByType(Class<?> owner, Class<?> type) {
        Field field = Arrays.stream(owner.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()) && f.getType() == type).findFirst().orElseThrow();
        field.setAccessible(true); return field;
    }
    private static <T> T initialValue(OptionInstance<T> instance) throws IllegalAccessException {
        Field field = Arrays.stream(OptionInstance.class.getDeclaredFields()).filter(f -> f.getType() == Object.class && Modifier.isFinal(f.getModifiers())).findFirst().orElseThrow();
        field.setAccessible(true); return (T)field.get(instance);
    }
    private static String section(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("sound") || lower.contains("music") || lower.contains("audio")) return "声音与音量";
        if (lower.contains("chat")) return "聊天";
        if (lower.contains("mouse") || lower.contains("sensitivity") || lower.contains("jump") || lower.contains("sprint") || lower.contains("crouch") || lower.contains("touchscreen")) return "操作";
        if (lower.contains("subtitle") || lower.contains("narrat") || lower.contains("contrast") || lower.contains("effect") || lower.contains("lightning") || lower.contains("tilt") || lower.contains("panorama")) return "无障碍";
        if (lower.contains("language") || lower.contains("unicode") || lower.contains("japanese")) return "语言与字体";
        if (lower.contains("telemetry") || lower.contains("server") || lower.contains("realms") || lower.contains("secure")) return "在线与隐私";
        return "画面与性能";
    }
}
