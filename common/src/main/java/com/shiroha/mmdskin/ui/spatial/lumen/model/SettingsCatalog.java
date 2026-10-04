package com.shiroha.mmdskin.ui.spatial.lumen.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Verified against the supplied MMD Skin source, Minecraft 1.21.4 mapped Options bytecode,
 * and bundled Vivecraft 1.21.4-1.3.15 VRSettings/VrOptions bytecode.
 * Values are a standalone demo, never read from or written to the reference installation.
 * Minecraft's memory-dependent distance cap is represented by its 64-bit maximum (32 chunks).
 */
public final class SettingsCatalog {
    private static final String CONFIG = "com.shiroha.mmdskin.config.ConfigData#";
    private static final String MODEL = "com.shiroha.mmdskin.config.ModelConfigData#";
    private static final String POINTER = "com.shiroha.mmdskin.config.VrPointerConfigData#";
    private static final String VIVE = "org.vivecraft.client_vr.settings.VRSettings#";
    private static final String MC = "net.minecraft.client.Options#";
    private static final List<Setting> ALL = build();

    private SettingsCatalog() {}
    public static List<Setting> all() { return ALL; }
    public static List<Setting> category(String category) {
        return ALL.stream().filter(s -> s.category().equals(category)).toList();
    }
    public static Setting find(String id) {
        return ALL.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown setting: " + id));
    }

    private static List<Setting> build() {
        List<Setting> out = new ArrayList<>();
        String c = "Minecraft", s = "画面与世界";
        slider(out, "mc.renderDistance", c, s, "渲染距离", "可见世界的区块半径；服从设备与服务器上限。", 2, 32, 1, 12, MC + "renderDistance()");
        slider(out, "mc.simulationDistance", c, s, "模拟距离", "实体与方块继续活动的区块半径。", 5, 32, 1, 12, MC + "simulationDistance()");
        slider(out, "mc.gamma", c, s, "亮度", "调整游戏画面的暗部亮度。", 0, 1, .05, .5, MC + "gamma()");
        slider(out, "mc.fov", c, s, "桌面视场角", "桌面 / 镜像画面的视场角；头显视场由运行时决定。", 30, 110, 1, 70, MC + "fov()");
        toggle(out, "mc.bobView", c, s, "视角摇晃", "原版行走摇晃偏好；VR 是否使用由 Vivecraft 决定。", true, MC + "bobView()");
        s = "声音与无障碍";
        slider(out, "mc.masterVolume", c, s, "主音量", "游戏内所有声音的总音量。", 0, 1, .05, 1, MC + "getSoundSourceOptionInstance(SoundSource.MASTER)");
        slider(out, "mc.musicVolume", c, s, "音乐音量", "世界背景音乐的音量。", 0, 1, .05, 1, MC + "getSoundSourceOptionInstance(SoundSource.MUSIC)");
        toggle(out, "mc.showSubtitles", c, s, "声音字幕", "以字幕提示环境声和声音方向。", false, MC + "showSubtitles()");

        c = "MMD Skin"; s = "渲染与风格";
        toggle(out, "mmd.openGLEnableLighting", c, s, "模型光照", "让 MMD 模型响应环境光照。", true, CONFIG + "openGLEnableLighting");
        toggle(out, "mmd.mmdShaderEnabled", c, s, "MMD 着色器", "启用 MMD 专用着色；更改后会重载模型。", false, CONFIG + "mmdShaderEnabled");
        toggle(out, "mmd.gpuSkinningEnabled", c, s, "GPU 骨骼蒙皮", "将骨骼顶点变换交由 GPU；更改后会重载模型。", false, CONFIG + "gpuSkinningEnabled");
        toggle(out, "mmd.gpuMorphEnabled", c, s, "GPU 表情变形", "将 Morph 运算交由 GPU；更改后会重载模型。", false, CONFIG + "gpuMorphEnabled");
        toggle(out, "mmd.toonRenderingEnabled", c, s, "卡通渲染", "使用分层明暗塑造二次元材质。", false, CONFIG + "toonRenderingEnabled");
        slider(out, "mmd.toonLevels", c, s, "明暗层数", "卡通渲染的明暗色阶数。", 2, 5, 1, 4, CONFIG + "toonLevels");
        toggle(out, "mmd.toonOutlineEnabled", c, s, "模型描边", "卡通渲染中的轮廓线。", true, CONFIG + "toonOutlineEnabled");
        slider(out, "mmd.toonOutlineWidth", c, s, "描边宽度", "采用配置归一化范围，避免过厚轮廓。", .001, .02, .0001, .0022, CONFIG + "toonOutlineWidth");
        s = "物理与动态";
        toggle(out, "mmd.physicsEnabled", c, s, "模型物理", "启用头发、衣摆与刚体模拟。", true, CONFIG + "physicsEnabled");
        slider(out, "mmd.physicsGravityY", c, s, "垂直重力", "MMD 物理坐标中的 Y 轴重力。", -200, -10, 1, -98, CONFIG + "physicsGravityY");
        slider(out, "mmd.physicsFps", c, s, "物理更新率", "每秒物理更新次数；范围来自原 Mod 设置界面。", 30, 120, 1, 60, CONFIG + "physicsFps");
        slider(out, "mmd.physicsMaxSubstepCount", c, s, "最大物理子步", "帧间隔变化时允许的最大补算步数。", 1, 10, 1, 5, CONFIG + "physicsMaxSubstepCount");
        slider(out, "mmd.physicsInertiaStrength", c, s, "惯性强度", "行走与转身带来的物理惯性倍率。", 0, 3, .05, .5, CONFIG + "physicsInertiaStrength");
        toggle(out, "mmd.physicsJointsEnabled", c, s, "刚体关节", "启用模型刚体之间的约束。", true, CONFIG + "physicsJointsEnabled");
        s = "当前模型";
        slider(out, "model.modelScale", c, s, "模型缩放", "当前模型的独立比例，按模型保存。", .5, 2, .05, 1, MODEL + "modelScale");
        toggle(out, "model.eyeTrackingEnabled", c, s, "眼球自动跟随", "根据游戏相机的位置，让模型眼球朝向观察相机。", true, MODEL + "eyeTrackingEnabled");
        slider(out, "model.eyeMaxAngle", c, s, "眼球最大转角", "眼球旋转限制，单位为弧度。", .05, 1, .0001, .1745, MODEL + "eyeMaxAngle");
        slider(out, "model.heldItemScale", c, s, "手持物品缩放", "双手共享的物品本地缩放。", .25, 2, .05, 1, MODEL + "heldItemScale");
        toggle(out, "mmd.firstPersonModelEnabled", c, s, "第一人称模型", "启用 Mod 第一人称模型显示偏好。", false, CONFIG + "firstPersonModelEnabled");
        s = "资源与诊断";
        slider(out, "mmd.modelPoolMaxCount", c, s, "模型缓存数量", "缓存中的模型数量上限。", 5, 100, 1, 20, CONFIG + "modelPoolMaxCount");
        slider(out, "mmd.textureCacheBudgetMB", c, s, "纹理缓存预算", "纹理缓存容量，单位 MB；更改后会重载模型。", 64, 1024, 16, 256, CONFIG + "textureCacheBudgetMB");
        toggle(out, "mmd.debugHudEnabled", c, s, "性能调试面板", "显示 Mod 的模型与性能统计。", false, CONFIG + "debugHudEnabled");

        c = "Vivecraft"; s = "移动与舒适度";
        toggle(out, "vive.seated", c, s, "坐姿模式", "Vivecraft 原生坐姿游玩模式。", false, VIVE + "seated");
        toggle(out, "vive.forceStandingFreeMove", c, s, "站姿自由移动", "站姿时强制使用摇杆自由移动。", false, VIVE + "forceStandingFreeMove");
        choice(out, "vive.worldRotationIncrement", c, s, "转向步进", "0 表示平滑转向；其他数值为每次转向角度。", "45", List.of("0", "10", "36", "45", "90", "180"), VIVE + "worldRotationIncrement");
        toggle(out, "vive.useFOVReduction", c, s, "移动视野收缩", "自由移动时缩小周边视野。", false, VIVE + "useFOVReduction");
        toggle(out, "vive.walkUpBlocks", c, s, "自动迈上方块", "Vivecraft 行走时自动迈上一格方块。", true, VIVE + "walkUpBlocks");
        toggle(out, "vive.reverseHands", c, s, "左右手互换", "切换 Vivecraft 主手偏好。", false, VIVE + "reverseHands");
        s = "空间与显示";
        choice(out, "vive.worldScale", c, s, "世界比例", "Vivecraft 原生离散倍率；服从服务器限制。", "1.0", List.of("0.1", "0.25", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0", "1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "1.8", "1.9", "2.0", "3.0", "4.0", "6.0", "8.0", "10.0", "12.0", "16.0", "20.0", "30.0", "50.0", "75.0", "100.0"), VIVE + "worldScale");
        slider(out, "vive.renderScaleFactor", c, s, "VR 渲染倍率", "Vivecraft 眼睛渲染目标的像素数量倍率。", .1, 9, .1, 1, VIVE + "renderScaleFactor");
        slider(out, "vive.hudOpacity", c, s, "HUD 不透明度", "Vivecraft 原生 HUD 的透明程度。", .15, 1, .05, 1, VIVE + "hudOpacity");
        s = "MMD 追踪与校准";
        toggle(out, "vr.enabled", c, s, "MMD · VR 联动", "启用 Mod 对 Vivecraft 的头部与手臂追踪。", false, CONFIG + "vrEnabled / IVRConfig#isVREnabled()");
        slider(out, "vr.armIKStrength", c, s, "全局手臂 IK", "全局 VR 手臂反向运动学权重。", 0, 1, .05, 1, CONFIG + "vrArmIKStrength / IVRConfig#getVRArmIKStrength()");
        slider(out, "model.vrArmIkStrength", c, s, "模型手臂 IK", "当前模型对全局 IK 强度的倍率。", 0, 1, .05, 1, MODEL + "vrArmIkStrength");
        slider(out, "model.vrArmLengthScale", c, s, "手臂触达倍率", "将用户触达映射到模型；不改变模型骨长。", .25, 4, .05, 1, MODEL + "vrArmLengthScale");
        slider(out, "model.vrEyeOffsetX", c, s, "头部锚点 · 左右", "模型追踪锚点偏移（米），不会移动头显相机。", -.5, .5, .01, 0, MODEL + "vrEyeOffsetX");
        slider(out, "model.vrEyeOffsetY", c, s, "头部锚点 · 上下", "模型追踪锚点偏移（米），不会移动头显相机。", -.5, .5, .01, 0, MODEL + "vrEyeOffsetY");
        slider(out, "model.vrEyeOffsetZ", c, s, "头部锚点 · 前后", "模型追踪锚点偏移（米），不会移动头显相机。", -.5, .5, .01, 0, MODEL + "vrEyeOffsetZ");
        s = "手指与拇指";
        toggle(out, "model.vrFingerTrackingEnabled", c, s, "手指追踪", "使用运行时提供的手指骨骼；关闭不影响头臂追踪。", true, MODEL + "vrFingerTrackingEnabled");
        toggle(out, "model.vrLeftHandTrackingEnabled", c, s, "左手手指追踪", "独立启用左手手指输入；不改变左臂与头部追踪。", true, MODEL + "vrLeftHandTrackingEnabled");
        toggle(out, "model.vrRightHandTrackingEnabled", c, s, "右手手指追踪", "独立启用右手手指输入；不改变右臂与头部追踪。", true, MODEL + "vrRightHandTrackingEnabled");
        thumbSettings(out, "vrLeftThumb", "左手");
        thumbSettings(out, "vrRightThumb", "右手");
        s = "指针与交互";
        choice(out, "pointer.visibility", c, s, "射线显示时机", "ALWAYS 常显 · UI_ONLY 界面中 · UI_HIT 命中时 · OFF 关闭。", "UI_ONLY", List.of("ALWAYS", "UI_ONLY", "UI_HIT", "OFF"), POINTER + "visibility");
        choice(out, "pointer.pressBinding", c, s, "按下反馈来源", "选择触发光点按压反馈的输入；不改变实际交互键位。", "TRIGGER", List.of("TRIGGER", "GRIP", "TRIGGER_OR_GRIP"), POINTER + "pressBinding");
        toggle(out, "pointer.leftEnabled", c, s, "左手射线", "绘制左手辅助射线；不改变左手实际交互能力。", true, POINTER + "leftEnabled");
        toggle(out, "pointer.rightEnabled", c, s, "右手射线", "绘制右手辅助射线；不改变右手实际交互能力。", true, POINTER + "rightEnabled");
        toggle(out, "pointer.dotEnabled", c, s, "命中光点", "在射线命中位置绘制光点。", true, POINTER + "dotEnabled");
        toggle(out, "pointer.hideOriginalCrosshair", c, s, "隐藏原版准星", "独立控制原版准星是否隐藏。", false, POINTER + "hideOriginalCrosshair");
        slider(out, "pointer.rayAlpha", c, s, "射线不透明度", "射线透明程度，0 为全透明，1 为不透明。", 0, 1, .05, .65, POINTER + "rayAlpha");
        slider(out, "pointer.rayWidth", c, s, "射线直径", "完整光束直径，单位米。", .0005, .02, .0005, .002, POINTER + "rayWidth");
        slider(out, "pointer.maxDistance", c, s, "射线最大距离", "界面指向的最大距离，单位米。", .25, 32, .25, 8, POINTER + "maxDistance");
        slider(out, "pointer.pressedDotRadiusView", c, s, "按压光点半径", "按压时光点半径占单眼视图高度的比例。", .0005, .025, .0005, .0045, POINTER + "pressedDotRadiusView");
        toggle(out, "pointer.colorAnimationEnabled", c, s, "按压颜色过渡", "在按下与释放之间平滑改变指针颜色。", true, POINTER + "colorAnimationEnabled");

        c = "界面"; s = "可读性与空间";
        toggle(out, "ui.reducedMotion", c, s, "减少动态效果", "关闭菜单位移动画并减弱环境动态。", false, "prototype:menu.reducedMotion");
        toggle(out, "ui.highContrast", c, s, "高对比度", "增强面板、边框与文字的对比。", false, "prototype:menu.highContrast");
        slider(out, "ui.panelDistance", c, s, "面板距离", "拖动时预览位置，松开扳机后移动菜单。距离单位为米。", .6, 2.5, .05, 1.35, "prototype:surface.distanceMetres");
        slider(out, "ui.panelScale", c, s, "面板大小", "面板物理尺寸倍率。", .75, 1.25, .05, 1, "prototype:surface.scale");
        return List.copyOf(out);
    }

    /** Six real fields per hand; all affect retargeting only, never asset geometry or HMD pose. */
    private static void thumbSettings(List<Setting> out, String side, String label) {
        String id = "model." + side + ".";
        String source = MODEL + side + ".";
        String category = "Vivecraft", section = "左右拇指校准";
        slider(out, id + "curlAxisOffsetDeg", category, section, label + " · 拇指弯曲方向",
                "绕拇指静止纵轴旋转弯曲平面，单位度；仅调整动作映射。", -180, 180, 1, 0, source + "curlAxisOffsetDeg");
        slider(out, id + "oppositionOffsetDeg", category, section, label + " · 向掌心收拢",
                "拇指对掌方向的静态修正，单位度。", -90, 90, 1, 0, source + "oppositionOffsetDeg");
        slider(out, id + "baseCurlOffsetDeg", category, section, label + " · 根部弯曲偏移",
                "拇指根部的静态弯曲修正，单位度。", -90, 90, 1, 0, source + "baseCurlOffsetDeg");
        slider(out, id + "baseCurlScale", category, section, label + " · 根节弯曲倍率",
                "拇指根节弯曲输入的倍率；1 为原始映射。", 0, 3, .05, 1, source + "baseCurlScale");
        slider(out, id + "middleCurlScale", category, section, label + " · 中节弯曲倍率",
                "拇指中节弯曲输入的倍率；1 为原始映射。", 0, 3, .05, 1, source + "middleCurlScale");
        slider(out, id + "tipCurlScale", category, section, label + " · 末节弯曲倍率",
                "拇指末节弯曲输入的倍率；1 为原始映射。", 0, 3, .05, 1, source + "tipCurlScale");
    }

    private static void toggle(List<Setting> out, String id, String c, String section, String title,
                               String description, boolean value, String source) {
        out.add(new Setting(id, c, section, title, description, Setting.Kind.TOGGLE, 0, 1, 1, value, List.of(), source));
    }
    private static void slider(List<Setting> out, String id, String c, String section, String title,
                               String description, double min, double max, double step, double value, String source) {
        out.add(new Setting(id, c, section, title, description, Setting.Kind.SLIDER, min, max, step, value, List.of(), source));
    }
    private static void choice(List<Setting> out, String id, String c, String section, String title,
                               String description, String value, List<String> options, String source) {
        out.add(new Setting(id, c, section, title, description, Setting.Kind.CHOICE, 0, options.size() - 1, 1, value, options, source));
    }
}
