package com.shiroha.mmdskin.ui.spatial.backend;

import com.shiroha.mmdskin.ui.spatial.lumen.model.Setting;
import net.minecraft.client.resources.language.I18n;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional dependency: preserves Vivecraft conversions, server limits, native callbacks and persistence. */
final class VivecraftSettingsAdapter {
    private final Object settings;
    private final Class<?> optionType;
    private final Class<?> settingsType;
    private final Map<Object, Runnable> pending = new LinkedHashMap<>();
    private final Map<Object, Object> preview = new HashMap<>();

    VivecraftSettingsAdapter() throws ReflectiveOperationException {
        settingsType = Class.forName("org.vivecraft.client_vr.settings.VRSettings");
        optionType = Class.forName("org.vivecraft.client_vr.settings.VRSettings$VrOptions");
        settings = settingsType.getField("INSTANCE").get(null);
        if (settings == null) throw new IllegalStateException("Vivecraft settings not initialized");
    }
    List<SettingBinding> bindings() {
        List<SettingBinding> rows = new ArrayList<>();
        Map<Object, Field> fields = new HashMap<>();
        Map<Object, String> configNames = new HashMap<>();
        try {
            for (Field field : settingsType.getFields()) {
                for (Annotation annotation : field.getDeclaredAnnotations()) {
                    if (!annotation.annotationType().getName().equals("org.vivecraft.client_vr.settings.SettingField")) continue;
                    Object option = annotation.annotationType().getMethod("value").invoke(annotation);
                    if (((Enum<?>)option).name().equals("DUMMY")) continue;
                    fields.put(option, field);
                    String configName = (String)annotation.annotationType().getMethod("config").invoke(annotation);
                    configNames.put(option, configName.isEmpty() ? field.getName() : configName);
                }
            }
            Field defaultsField = settingsType.getDeclaredField("defaultsMap"); defaultsField.setAccessible(true);
            Map<String, String> defaults = (Map<String, String>)defaultsField.get(settings);
            for (Object option : optionType.getEnumConstants()) {
                String name = ((Enum<?>)option).name();
                if (name.equals("DUMMY")) continue;
                Field field = fields.get(option);
                String type = call(option, "getType").toString();
                String id = "vive." + name;
                String key = "vivecraft.options." + name;
                String title = I18n.exists(key) ? I18n.get(key) : name.replace('_', ' ');
                String tooltip = I18n.exists(key + ".tooltip") ? I18n.get(key + ".tooltip") : "调整" + title + "。服务器锁定的项目无法修改。";
                String defaultText = defaults.get(configNames.get(option));
                String section = section(name);
                Supplier<String> display = () -> (String)invoke("getButtonDisplayString", new Class<?>[]{optionType, boolean.class}, option, false);
                Supplier<Object> raw = () -> field == null ? display.get() : read(field);
                Consumer<Object> cycle = ignored -> queue(option, null, () -> invoke("setOptionValue", new Class<?>[]{optionType}, option));
                Setting schema;
                Supplier<Object> read;
                Consumer<Object> write;
                if (type.equals("LIMITED_FLOAT") && field != null) {
                    double min = ((Number)call(option, "getValueMin")).doubleValue();
                    double max = ((Number)call(option, "getValueMax")).doubleValue();
                    Field stepField = optionType.getDeclaredField("valueStep"); stepField.setAccessible(true);
                    double step = Math.max(.0001, stepField.getFloat(option));
                    float initial = ((Number)invoke("getOptionFloatValue", new Class<?>[]{optionType}, option)).floatValue();
                    if (defaultText != null) {
                        try {
                            Method convert = optionType.getDeclaredMethod("getOptionFloatValue", float.class); convert.setAccessible(true);
                            float rawDefault = Float.parseFloat(defaultText); Object converted = convert.invoke(option, rawDefault);
                            initial = converted == null ? rawDefault : ((Number)converted).floatValue();
                        } catch (ReflectiveOperationException | NumberFormatException ignored) {}
                    }
                    schema = new Setting(id, "Vivecraft", section, title, tooltip, Setting.Kind.SLIDER,
                            min, max, step, Math.clamp(initial, min, max), List.of(), settingsType.getName() + "#" + field.getName());
                    read = () -> preview.containsKey(option) ? preview.get(option) : invoke("getOptionFloatValue", new Class<?>[]{optionType}, option);
                    write = value -> queue(option, value, () -> invoke("setOptionFloatValue", new Class<?>[]{optionType, float.class}, option, ((Number)value).floatValue()));
                } else if (field != null && field.getType() == boolean.class) {
                    boolean initial = defaultText == null ? (Boolean)raw.get() : Boolean.parseBoolean(defaultText);
                    schema = new Setting(id, "Vivecraft", section, title, tooltip, Setting.Kind.TOGGLE, 0, 1, 1, initial, List.of(), key);
                    read = () -> preview.getOrDefault(option, raw.get());
                    write = value -> queue(option, value, () -> { if (!Objects.equals(raw.get(), value)) invoke("setOptionValue", new Class<?>[]{optionType}, option); });
                } else if (field != null && field.getType().isEnum()) {
                    List<String> domain = Arrays.stream(field.getType().getEnumConstants()).map(v -> ((Enum<?>)v).name()).toList();
                    if (domain.size() < 2) continue;
                    String initial = domain.contains(defaultText) ? defaultText : ((Enum<?>)raw.get()).name();
                    schema = new Setting(id, "Vivecraft", section, title, tooltip, Setting.Kind.CHOICE, 0, 1, 1, initial, domain, key);
                    read = () -> preview.getOrDefault(option, ((Enum<?>)raw.get()).name());
                    write = value -> queue(option, value, () -> invoke("setOptionValue", new Class<?>[]{optionType, Object.class}, option, Enum.valueOf((Class)field.getType(), value.toString())));
                } else if (field != null && field.getType() == String.class && type.equals("KEYMAPPING")) {
                    schema = new Setting(id, "Vivecraft", section, title, tooltip + " 输入按键映射名称，例如 key.inventory。", Setting.Kind.TEXT,
                            0, 1, 1, defaultText == null ? raw.get() : defaultText, List.of(), key);
                    read = () -> preview.getOrDefault(option, raw.get());
                    write = value -> queue(option, value, () -> invoke("setOptionValue", new Class<?>[]{optionType, Object.class}, option, value));
                } else {
                    schema = new Setting(id, "Vivecraft", section, title, tooltip, Setting.Kind.ACTION, 0, 1, 1, "", List.of(), key);
                    read = display::get; write = cycle;
                }
                rows.add(new SettingBinding(schema, read, write,
                        () -> preview.containsKey(option) ? String.valueOf(preview.get(option)) : display.get(),
                        () -> editable(option), () -> {
                    pending.remove(option); preview.remove(option);
                    invoke("loadDefault", new Class<?>[]{optionType}, option);
                    invoke("saveOptions", new Class<?>[]{});
                }));
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        return rows;
    }
    private void queue(Object option, Object value, Runnable action) {
        if (!editable(option)) throw new IllegalArgumentException("该设置被服务器或当前模式锁定");
        if (value != null) preview.put(option, value);
        pending.put(option, action);
    }
    private boolean editable(Object option) {
        try {
            if (!(Boolean)call(option, "isChangeable")) return false;
            Object overrides = settingsType.getField("overrides").get(settings);
            if ((Boolean)overrides.getClass().getMethod("hasSetting", optionType).invoke(overrides, option)) {
                Object entry = overrides.getClass().getMethod("getSetting", optionType).invoke(overrides, option);
                if ((Boolean)call(entry, "isValueOverridden")) return false;
            }
            return true;
        } catch (ReflectiveOperationException failure) { return false; }
    }
    void flush() {
        List<Map.Entry<Object, Runnable>> work = new ArrayList<>(pending.entrySet());
        pending.clear(); preview.clear();
        for (var entry : work) if (editable(entry.getKey())) entry.getValue().run();
    }
    private Object read(Field field) {
        try { return field.get(settings); } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    private Object invoke(String method, Class<?>[] types, Object... arguments) {
        try { return settingsType.getMethod(method, types).invoke(settings, arguments); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static Object call(Object value, String method) {
        try {
            Method target = value.getClass().getMethod(method); target.setAccessible(true); return target.invoke(value);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static String section(String name) {
        if (name.contains("KEYBOARD") || name.startsWith("RADIAL") || name.contains("BINDING")) return "键盘、轮盘与按键";
        if (name.contains("HUD") || name.contains("GUI") || name.contains("CROSSHAIR") || name.contains("CHAT") || name.contains("OUTLINE")) return "界面与准心";
        if (name.contains("MIRROR") || name.contains("CAMERA") || name.contains("MIXED_REALITY") || name.contains("MONO_FOV")) return "镜像与摄影";
        if (name.contains("PLAYER") || name.contains("WORLDSCALE") || name.contains("THIRDPERSON")) return "玩家身体与手臂";
        if (name.startsWith("NULLVR") || name.contains("DEBUG") || name.contains("TRACKER") || name.equals("CONTROLLER_TRANSFORM")) return "追踪与调试";
        if (name.contains("MOV") || name.contains("WALK") || name.contains("ROTATION") || name.contains("SEATED") || name.contains("SENSITIVITY") || name.contains("SPRINT") || name.contains("FOV_REDUCTION") || name.contains("WORLD") || name.contains("TELEPORT") || name.equals("RESET_ORIGIN")) return "移动、转向与舒适度";
        if (name.contains("REALISTIC") || name.contains("COLLISION") || name.contains("CLIMB") || name.contains("BOW") || name.contains("BACKPACK") || name.contains("HANDS") || name.contains("HOTBAR")) return "交互与动作";
        if (name.contains("RENDER") || name.contains("STENCIL") || name.contains("SHADER") || name.contains("EFFECT") || name.contains("INDICATOR") || name.equals("FSAA")) return "画面与渲染";
        return "通用与高级";
    }
}
