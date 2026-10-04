package com.shiroha.mmdskin.ui.spatial.backend;

import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.config.*;
import com.shiroha.mmdskin.renderer.runtime.mode.RenderModeManager;
import com.shiroha.mmdskin.renderer.runtime.model.MMDModelManager;
import com.shiroha.mmdskin.ui.selector.ModelSelectorServices;
import com.shiroha.mmdskin.ui.spatial.lumen.model.Setting;
import com.shiroha.mmdskin.ui.spatial.lumen.model.SettingsCatalog;
import net.minecraft.client.resources.language.I18n;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Enumerates every primitive MMD setting; compound resource selectors have dedicated actions. */
final class MmdSettingsAdapter {
    private final ConfigData global;
    private final Method saveGlobal;
    private final SpatialMenuPreferences ui = SpatialMenuPreferences.load();
    private final Set<String> changedGlobal = new HashSet<>();
    private ModelConfigData model;
    private String modelName;
    private VrPointerConfigData pointer = VrPointerConfigManager.getConfig();
    private boolean modelDirty, pointerDirty, uiDirty, stageDirty;
    private final Map<String, Setting> known = new HashMap<>();

    MmdSettingsAdapter(String modelName) throws ReflectiveOperationException {
        Class<?> platform;
        try { platform = Class.forName("com.shiroha.mmdskin.neoforge.config.MmdSkinConfig"); }
        catch (ClassNotFoundException ignored) { platform = Class.forName("com.shiroha.mmdskin.fabric.config.MmdSkinConfig"); }
        global = (ConfigData) platform.getMethod("getData").invoke(null);
        saveGlobal = platform.getMethod("save");
        for (Setting setting : SettingsCatalog.all()) known.put(setting.id(), setting);
        selectModel(modelName);
    }

    void selectModel(String name) {
        if (modelDirty) ModelSelectorServices.modelSettings().save(modelName, model);
        modelName = name == null ? UIConstants.DEFAULT_MODEL_NAME : name;
        model = ModelSelectorServices.modelSettings().loadEditableConfig(modelName);
        modelDirty = false;
    }
    String modelName() { return modelName; }
    boolean favorite(String id) { return ui.favorites.contains(id); }
    void toggleFavorite(String id) { if (!ui.favorites.remove(id)) ui.favorites.add(id); uiDirty = true; }
    boolean hasModel() { return modelName != null && !modelName.isBlank() && !modelName.equals(UIConstants.DEFAULT_MODEL_NAME); }

    List<SettingBinding> bindings() {
        List<SettingBinding> result = new ArrayList<>();
        fields(result, "mmd.", ConfigData.class, () -> global, new ConfigData(), field -> {
            changedGlobal.add(field);
        });
        fields(result, "model.", ModelConfigData.class, () -> model, new ModelConfigData(), field -> {
            model.normalizeInPlace(); ModelSelectorServices.modelSettings().preview(modelName, model); modelDirty = true;
        });
        fields(result, "model.vrLeftThumb.", ModelConfigData.ThumbCalibration.class, () -> model.vrLeftThumb,
                new ModelConfigData.ThumbCalibration(), field -> previewModel());
        fields(result, "model.vrRightThumb.", ModelConfigData.ThumbCalibration.class, () -> model.vrRightThumb,
                new ModelConfigData.ThumbCalibration(), field -> previewModel());
        fields(result, "pointer.", VrPointerConfigData.class, () -> pointer, new VrPointerConfigData(), field -> {
            pointer.normalizeInPlace(); VrPointerConfigManager.preview(pointer); pointerDirty = true;
        });
        fields(result, "ui.", SpatialMenuPreferences.class, () -> ui, new SpatialMenuPreferences(), field -> { ui.normalize(); uiDirty = true; });
        fields(result, "stage.", StageConfig.class, StageConfig::getInstance,
                new com.google.gson.Gson().fromJson("{}", StageConfig.class), field -> stageDirty = true);
        return result;
    }
    private void previewModel() {
        model.normalizeInPlace(); ModelSelectorServices.modelSettings().preview(modelName, model); modelDirty = true;
    }

    private void fields(List<SettingBinding> out, String prefix, Class<?> type, Supplier<Object> data,
                        Object defaults, Consumer<String> changed) {
        for (Field field : type.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            Class<?> t = field.getType();
            if (!(t == boolean.class || t == int.class || t == float.class || t == double.class || t.isEnum())) continue;
            String id = prefix + field.getName();
            String category = prefix.equals("ui.") ? "界面" : "MMD Skin";
            String section = section(prefix, field.getName());
            try {
                Object defaultRaw = field.get(defaults);
                List<String> choices = t.isEnum() ? Arrays.stream(t.getEnumConstants()).map(Object::toString).toList() : List.of();
                Setting.Kind kind = t == boolean.class ? Setting.Kind.TOGGLE : t.isEnum() ? Setting.Kind.CHOICE
                        : isColor(field.getName()) ? Setting.Kind.TEXT : Setting.Kind.SLIDER;
                Object initial = t.isEnum() ? defaultRaw.toString() : isColor(field.getName()) ? color((Number) defaultRaw) : defaultRaw;
                double[] range = bounds(prefix, field.getName(), defaultRaw);
                Setting prior = known.get(id);
                String key = translationKey(prefix, field.getName());
                String title = I18n.exists(key) ? I18n.get(key) : prior != null ? prior.title() : humanName(prefix, field.getName());
                String description = I18n.exists(key + ".tooltip") ? I18n.get(key + ".tooltip") : prior != null ? prior.description()
                        : kind == Setting.Kind.TEXT ? "输入六位 RGB 颜色，例如 #66CCFF。" : title + "；修改后实时预览，关闭菜单时保存。";
                if (prefix.equals("ui.")) description = uiDescription(field.getName(), description);
                Setting schema = new Setting(id, category, section, title, description, kind,
                        range[0], range[1], range[2], initial, choices, type.getName() + "#" + field.getName());
                Supplier<Object> read = () -> {
                    try { Object value = field.get(data.get()); return t.isEnum() ? value.toString()
                            : isColor(field.getName()) ? color((Number) value) : value; }
                    catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                };
                Consumer<Object> write = value -> {
                    try {
                        Object next = t.isEnum() ? Enum.valueOf((Class)t, value.toString())
                                : isColor(field.getName()) ? parseColor(value.toString()) : convert(value, t);
                        if (Objects.equals(field.get(data.get()), next)) return;
                        if (prefix.equals("ui.") && field.getName().equals("panelDistance")) ui.setPanelDistance(((Number)next).doubleValue());
                        else field.set(data.get(), next);
                        changed.accept(field.getName());
                    } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                };
                out.add(new SettingBinding(schema, read, write, () -> {
                    Object value = read.get();
                    if (t.isEnum()) {
                        if (prefix.equals("ui.") && field.getName().equals("detailBehavior"))
                            return com.shiroha.mmdskin.ui.spatial.SpatialMenuTransforms.DetailBehavior.parse(value.toString()).label();
                        String enumKey = prefix.equals("pointer.") ? "gui.mmdskin.pointer." + (field.getName().equals("hideCrosshairMode") ? "crosshair_mode" : field.getName())
                                + "." + value.toString().toLowerCase(Locale.ROOT) : "gui.mmdskin.keyboard.mode." + value.toString().toLowerCase(Locale.ROOT);
                        return I18n.exists(enumKey) ? I18n.get(enumKey) : value.toString();
                    }
                    return String.valueOf(value);
                }, () -> (!prefix.startsWith("model.") || hasModel()) && (!field.getName().matches("vrKeyboardDrag(Position|Rotation)Ms") || global.vrKeyboardDragSmoothing)
                        && (!prefix.equals("ui.") || uiEnabled(field.getName())),
                        () -> write.accept(initial)));
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }
    void flush() throws Exception {
        if (!changedGlobal.isEmpty()) {
            // Keep cross-field LOD invariants identical to loading the regular config.
            var normalize = ConfigData.class.getDeclaredMethod("normalize"); normalize.setAccessible(true); normalize.invoke(global);
            saveGlobal.invoke(null);
            if (changedGlobal.contains("gpuSkinningEnabled")) RenderModeManager.setUseGpuSkinning(global.gpuSkinningEnabled);
            if (changedGlobal.stream().anyMatch(Set.of("gpuSkinningEnabled", "gpuMorphEnabled", "mmdShaderEnabled", "maxBones", "textureCacheBudgetMB")::contains))
                MMDModelManager.forceReloadAllModels();
            if (changedGlobal.stream().anyMatch(name -> name.startsWith("physics"))) {
                NativeFunc.GetInst().SetPhysicsConfig(global.physicsEnabled, global.physicsGravityY, global.physicsFps,
                        global.physicsMaxSubstepCount, global.physicsInertiaStrength, global.physicsMaxLinearVelocity,
                        global.physicsMaxAngularVelocity, global.physicsJointsEnabled, global.physicsKinematicFilter, global.physicsDebugLog);
            }
            changedGlobal.clear();
        }
        if (modelDirty) { ModelSelectorServices.modelSettings().save(modelName, model); modelDirty = false; }
        if (pointerDirty) {
            if (!VrPointerConfigManager.save(pointer)) throw new java.io.IOException("辅助瞄准配置保存失败");
            pointerDirty = false;
        }
        if (uiDirty) { ui.save(); uiDirty = false; }
        if (stageDirty) { StageConfig.getInstance().save(); stageDirty = false; }
    }
    static Object convert(Object value, Class<?> type) {
        if (type == int.class) return ((Number)value).intValue();
        if (type == float.class) return ((Number)value).floatValue();
        if (type == double.class) return ((Number)value).doubleValue();
        return value;
    }
    private static boolean isColor(String name) { return name.endsWith("Color"); }
    private static String color(Number value) { return String.format(Locale.ROOT, "#%06X", value.intValue() & 0xFFFFFF); }
    private static int parseColor(String value) {
        String digits = value.startsWith("#") ? value.substring(1) : value;
        if (!digits.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("请输入六位 RGB 颜色，例如 #66CCFF");
        return Integer.parseInt(digits, 16);
    }
    private static String section(String prefix, String name) {
        if (prefix.startsWith("model.vrLeftThumb")) return "手指追踪 / 左拇指";
        if (prefix.startsWith("model.vrRightThumb")) return "手指追踪 / 右拇指";
        if (prefix.equals("model.")) return name.startsWith("heldItem") ? "当前模型 / 手持物品"
                : name.contains("Tracking") && name.startsWith("vr") ? "手指追踪 / 模式" : name.startsWith("vr") ? "当前模型 / VR 校准" : "当前模型 / 基础";
        if (prefix.equals("pointer.")) return name.startsWith("pressed") || name.contains("Animation") || name.contains("Duration") || name.startsWith("fade")
                ? "辅助瞄准 / 动画" : name.startsWith("dot") ? "辅助瞄准 / 圆点" : name.startsWith("ray") || name.equals("maxDistance") ? "辅助瞄准 / 射线" : "辅助瞄准 / 交互";
        if (prefix.equals("ui.")) return "空间菜单";
        if (prefix.equals("stage.")) return "舞台 / 播放";
        if (name.startsWith("vrKeyboard")) return "VR 联动 / 虚拟键盘";
        if (name.startsWith("vr")) return "VR 联动 / 基础";
        if (name.startsWith("physics") || name.contains("Physics")) return "物理 / 预算与模拟";
        if (name.startsWith("toon")) return "渲染 / 卡通风格";
        if (name.startsWith("firstPerson")) return "渲染 / 第一人称";
        if (name.contains("Lod") || name.startsWith("performance") || name.startsWith("max") || name.contains("Cache") || name.contains("Pool")) return "性能 / 缓存与预算";
        return "渲染 / 基础";
    }
    private static String translationKey(String prefix, String name) {
        String suffix = switch (name) {
            case "openGLEnableLighting" -> "opengl_lighting"; case "mmdShaderEnabled" -> "mmd_shader";
            case "firstPersonModelEnabled" -> "first_person_model"; case "gpuSkinningEnabled" -> "gpu_skinning";
            case "gpuMorphEnabled" -> "gpu_morph"; case "modelPoolMaxCount" -> "model_pool_max";
            case "vrArmIKStrength" -> "vr_arm_ik_strength"; case "vrKeyboardEnabled" -> "vr_keyboard";
            case "vrKeyboardImeEnabled" -> "vr_keyboard_ime"; case "vrKeyboardDragPositionMs" -> "vr_keyboard_drag_position";
            case "vrKeyboardDragRotationMs" -> "vr_keyboard_drag_rotation";
            case "physicsGravityY" -> "physics_gravity"; case "physicsMaxSubstepCount" -> "physics_substeps";
            case "physicsInertiaStrength" -> "physics_inertia"; case "debugHudEnabled" -> "debug_hud";
            default -> name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        };
        return "gui.mmdskin." + (prefix.equals("pointer.") ? "pointer." : "mod_settings.") + suffix;
    }
    private static String humanName(String prefix, String name) {
        return switch (name) {
            case "detailBehavior" -> "详细菜单空间行为"; case "closeDistance" -> "超距关闭距离（米）";
            case "positionSmoothingMs" -> "菜单平移缓动（ms）"; case "rotationSmoothingMs" -> "菜单旋转缓动（ms）";
            case "leftTriggerInteraction" -> "启用左手扳机键交互"; case "hideCrosshairMode" -> "隐藏原准心";
            case "rayColor" -> "射线颜色"; case "dotColor" -> "圆点颜色"; case "pressedRayColor" -> "按下时射线颜色";
            case "pressedDotColor" -> "按下时圆点颜色"; case "dotAlpha" -> "圆点透明度";
            case "dotRadiusView" -> "圆点视角半径"; case "colorDurationMs" -> "颜色过渡时间 (ms)";
            case "radiusAnimationEnabled" -> "圆点半径动画"; case "radiusDurationMs" -> "半径过渡时间 (ms)";
            case "fadeInMs" -> "显示淡入时间 (ms)"; case "fadeOutMs" -> "隐藏淡出时间 (ms)";
            case "vrKeyboardDragSmoothing" -> "键盘拖拽缓动"; case "heldItemOffsetX" -> "物品左右偏移";
            case "heldItemOffsetY" -> "物品上下偏移"; case "heldItemOffsetZ" -> "物品前后偏移";
            case "heldItemRotationX" -> "物品 X 轴旋转"; case "heldItemRotationY" -> "物品 Y 轴旋转";
            case "heldItemRotationZ" -> "物品 Z 轴旋转"; case "cinematicMode" -> "电影镜头";
            case "cameraHeightOffset" -> "舞台相机高度偏移"; case "audioVolume" -> "舞台音量";
            default -> name.replaceAll("([a-z])([A-Z])", "$1 $2");
        };
    }
    private double[] bounds(String prefix, String name, Object value) {
        if (isColor(name)) return new double[]{0, 1, 1};
        Setting existing = known.get(prefix + name);
        if (existing != null && existing.kind() == Setting.Kind.SLIDER) return new double[]{existing.min(), existing.max(), existing.step()};
        if (!(value instanceof Number number)) return new double[]{0, 1, 1};
        if (name.endsWith("Ms")) return new double[]{0, name.startsWith("vrKeyboard") ? 1000 : 2000, 10};
        if (name.startsWith("heldItemOffset")) return new double[]{-1, 1, .005};
        if (name.startsWith("heldItemRotation")) return new double[]{-180, 180, 1};
        if (name.startsWith("toonShadow") || name.matches("toonOutline[RGB]") || name.endsWith("Intensity") || name.endsWith("Alpha") || name.equals("audioVolume")) return new double[]{0, 1, .01};
        return switch (name) {
            case "closeDistance" -> new double[]{1.3, 32, .1};
            case "maxBones" -> new double[]{64, 8192, 64};
            case "vrArmIKStrength" -> new double[]{0, 1, .01};
            case "performanceLogIntervalSeconds" -> new double[]{1, 120, 1};
            case "maxVisibleModelsPerFrame", "maxPhysicsModelsPerFrame" -> new double[]{1, 100, 1};
            case "animationLodMediumDistance", "animationLodFarDistance", "physicsLodMaxDistance" -> new double[]{0, 256, 1};
            case "animationLodMediumUpdateInterval", "animationLodFarUpdateInterval" -> new double[]{1, 60, 1};
            case "toonRimPower" -> new double[]{.1, 10, .1}; case "toonSpecularPower" -> new double[]{1, 128, 1};
            case "physicsMaxLinearVelocity", "physicsMaxAngularVelocity" -> new double[]{0, 100, 1};
            case "firstPersonCameraForwardOffset" -> new double[]{-.1, .5, .005};
            case "firstPersonCameraVerticalOffset" -> new double[]{-.5, .5, .005};
            case "dotRadiusView", "pressedDotRadiusView" -> new double[]{.0005, .025, .0001};
            case "cameraHeightOffset" -> new double[]{-2, 2, .01};
            default -> throw new IllegalStateException("Missing numeric setting bounds: " + prefix + name + " = " + number);
        };
    }
    private boolean uiEnabled(String name) {
        return switch (name) {
            case "closeDistance" -> ui.detailBehavior == com.shiroha.mmdskin.ui.spatial.SpatialMenuTransforms.DetailBehavior.FIXED_CLOSE;
            case "positionSmoothingMs", "rotationSmoothingMs" -> !ui.reducedMotion;
            default -> true;
        };
    }
    private static String uiDescription(String name, String fallback) {
        return switch (name) {
            case "panelDistance" -> "拖动时预览位置，松开扳机后移动菜单。距离单位为米。超距关闭距离会按原比例一起增减，并始终大于打开距离的两倍。";
            case "detailBehavior" -> "固定面板可选择超距关闭或始终保留；跟随玩家只响应位移；跟随视角同时响应头显朝向。仅影响详细菜单。";
            case "closeDistance" -> "头显与固定面板超过此距离时关闭（米）。必须大于打开距离的两倍；过小的值会自动调整为两倍加 0.1 米。";
            case "positionSmoothingMs" -> "详细菜单平移的平滑响应时间，默认 180 ms，0 为立即移动。按实际经过时间计算，不受帧率影响；瞬移时直接重新定位。";
            case "rotationSmoothingMs" -> "详细菜单朝向的平滑响应时间，默认 180 ms，0 为立即转向。只改变水平朝向和俯仰，不随头显左右倾斜。";
            default -> fallback;
        };
    }
}
