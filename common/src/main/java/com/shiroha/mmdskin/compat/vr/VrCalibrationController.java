package com.shiroha.mmdskin.compat.vr;

import com.shiroha.mmdskin.bridge.runtime.NativeModelBridgePorts;
import com.shiroha.mmdskin.player.model.PlayerModelResolver;
import com.shiroha.mmdskin.renderer.integration.ModelPropertyHelper;
import com.shiroha.mmdskin.renderer.runtime.model.MMDModelManager;
import com.shiroha.mmdskin.ui.selector.VrModelSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** T-pose capture stays in-world so Vivecraft's actual attack action remains available. */
public final class VrCalibrationController {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Object INPUT_OWNER = new Object();
    private static Session session;

    private VrCalibrationController() {}

    public static boolean isActive() { return session != null; }

    /** Immutable display data for the head-centred world panel, independent of Vivecraft's wrist HUD. */
    public record PanelState(Component status, float progress, boolean ready, boolean triggerDown,
                             boolean armed, int detectedPresses, boolean completed, int secondsRemaining) {}

    public static PanelState panelState() {
        Session current = session;
        if (current == null) return null;
        int seconds = (int) Math.max(0, 60 - (System.nanoTime() - current.startedAt) / 1_000_000_000L);
        return new PanelState(current.status, current.stability.progress(), current.stability.ready(),
                current.triggerDown, current.armed, current.detectedPresses, current.result != null, seconds);
    }

    public static VrCalibrationMath.ModelMetrics modelMetrics(String modelName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !modelName.equals(VrLinkedActions.currentModelName())) return null;
        try {
            MMDModelManager.Model model = MMDModelManager.GetModel(modelName, PlayerModelResolver.getCacheKey(mc.player));
            if (model == null) return null;
            model.loadModelProperties(false);
            float baseScale = 0.09f * ModelPropertyHelper.getModelSize(model.properties)[0];
            float[] dimensions = NativeModelBridgePorts.modelPort().getVrCalibrationDimensions(model.model.getModelHandle());
            return VrCalibrationMath.ModelMetrics.fromNative(dimensions, baseScale);
        } catch (RuntimeException error) {
            LOGGER.debug("Cannot read model calibration dimensions", error);
            return null;
        }
    }

    public static boolean start(String modelName, VrModelSettingsScreen owner) {
        Minecraft mc = Minecraft.getInstance();
        if (session != null || mc.player == null || mc.level == null || !VRArmHider.isLocalVrRuntimeActive()) {
            owner.showCalibrationError(text("needs_vr", "请先进入 Vivecraft VR 模式再进行站姿校准。"));
            return false;
        }
        VrCalibrationMath.ModelMetrics metrics = modelMetrics(modelName);
        if (metrics == null) {
            owner.showCalibrationError(text("model_missing", "模型缺少可校准的眼部或手臂骨骼，请使用手动调整。"));
            return false;
        }
        session = new Session(modelName, owner, mc.level, metrics);
        VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, true);
        mc.setScreen(null);
        message("release_first", "站立并将双臂向两侧平伸成 T 字；先松开破坏/攻击扳机。菜单键取消。");
        return true;
    }

    /** Called once per client tick before normal interaction input is dispatched. */
    public static void tick() {
        Session current = session;
        if (current == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level != current.level || !VRArmHider.isLocalVrRuntimeActive()
                || !current.modelName.equals(VrLinkedActions.currentModelName())) {
            finish(null, false);
            return;
        }
        if (mc.screen != null || System.nanoTime() - current.startedAt > 60_000_000_000L) {
            finish(null, true);
            current.owner.showCalibrationError(text("cancelled", "校准已取消，保留进入校准前的调整。"));
            return;
        }
        VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, true);
        boolean down = VivecraftMirrorInput.readAnyTrigger();
        current.triggerDown = down;
        boolean risingEdge = down && !current.previousDown;
        if (risingEdge) current.detectedPresses++;
        boolean newTick = current.lastPlayerTick != mc.player.tickCount;
        if (newTick) {
            current.lastPlayerTick = mc.player.tickCount;
            current.ageTicks++;
        }
        if (current.result != null) {
            if (!down) finish(current.result, true);
            else message("release_finish", "校准完成：松开扳机返回设置，预览后点击保存。");
            return;
        }
        if (!current.armed) {
            if (down) current.releaseTicks = 0;
            else if (newTick) current.releaseTicks++;
            // Allow action-set switching to settle; an opening GUI click must never confirm calibration.
            current.armed = current.ageTicks >= 8 && current.releaseTicks >= 4;
        }
        var floor = VRDataProvider.getRenderOrigin(mc.player, 1);
        VrCalibrationMath.Measurement measurement = VrCalibrationMath.measure(
                VRDataProvider.getRenderTrackingData(mc.player), (float) floor.y);
        if (!mc.player.onGround() || mc.player.isCrouching() || mc.player.isSwimming()
                || mc.player.isFallFlying() || mc.player.isPassenger())
            measurement = VrCalibrationMath.Measurement.failure(VrCalibrationMath.Problem.STANDING);
        if (!measurement.usable()) current.stability.clear();
        else if (newTick) current.stability.add(measurement);
        boolean pressed = current.armed && risingEdge;
        current.previousDown = down;
        VrCalibrationMath.Measurement average = current.stability.average();
        boolean stillStable = current.stability.ready() && measurement.usable()
                && Math.abs(measurement.eyeHeightMetres() - average.eyeHeightMetres()) < 0.035f
                && Math.abs(measurement.spanMetres() - average.spanMetres()) < 0.05f;
        if (risingEdge) LOGGER.info("VR calibration trigger detected: armed={}, pose={}, stable={}, progress={}",
                current.armed, measurement.problem(), stillStable, current.stability.progress());
        if (pressed && stillStable) {
            current.rejectedPress = false;
            VrCalibrationMath.Result result = VrCalibrationMath.solve(current.metrics, current.stability.average());
            LOGGER.info("VR calibration solve: height={}, span={}, result={}, bodyScale={}, reachRatio={}",
                    average.eyeHeightMetres(), average.spanMetres(), result.problem(), result.modelScale(), result.armLengthScale());
            if (result.usable()) {
                current.result = result;
                message("release_finish", "校准完成：松开扳机返回设置，预览后点击保存。");
                return;
            }
            current.problem = result.problem();
            current.problemTicks = 60;
        } else if (pressed) {
            current.problem = measurement.problem();
            current.problemTicks = 40;
            current.rejectedPress = true;
        }
        if (!current.armed) {
            message("release_first", "站立并将双臂向两侧平伸成 T 字；先松开破坏/攻击扳机。菜单键取消。");
        } else if (current.problemTicks > 0) {
            if (newTick) current.problemTicks--;
            if (current.rejectedPress) {
                current.status = text("trigger_received", "已检测到扳机。").copy().append(problemText(current.problem));
            } else showProblem(current.problem);
        } else if (current.stability.ready()) {
            message("ready", "姿势稳定：按破坏/攻击扳机确认校准，跟随你的控制器绑定。菜单键取消。");
        } else {
            showProblem(measurement.problem());
        }
    }

    private static void finish(VrCalibrationMath.Result result, boolean returnToSettings) {
        Session previous = session;
        session = null;
        VivecraftMirrorInput.setInteractionPriority(INPUT_OWNER, false, false);
        VrCalibrationHudRenderer.release();
        if (previous != null) previous.owner.finishCalibration(result, returnToSettings);
    }

    private static void showProblem(VrCalibrationMath.Problem problem) {
        if (session != null) session.status = problemText(problem);
    }

    private static Component problemText(VrCalibrationMath.Problem problem) {
        return switch (problem) {
            case TRACKING -> text("tracking", "无法读取头显和双手：确认设备正在追踪，然后重试。");
            case STANDING -> text("standing", "请站在平地上保持正常站姿，不要蹲下或跳跃。");
            case RANGE -> text("range", "当前姿势与模型比例差异过大，请检查 T 字站姿或改用手动调整。");
            case MODEL -> text("model_missing", "模型缺少可校准的眼部或手臂骨骼，请使用手动调整。");
            case NONE -> text("hold_still", "还需要保持姿势稳定片刻；松开扳机后再按一次。");
            default -> text("pose", "面朝前方，双臂向两侧水平平伸成 T 字，并保持片刻。菜单键取消。");
        };
    }

    private static void message(String key, String fallback) {
        if (session != null) session.status = text(key, fallback);
    }

    private static Component text(String key, String fallback) {
        return Component.translatableWithFallback("message.mmdskin.vr_calibration." + key, fallback);
    }

    private static final class Session {
        private final String modelName;
        private final VrModelSettingsScreen owner;
        private final ClientLevel level;
        private final VrCalibrationMath.ModelMetrics metrics;
        private final long startedAt = System.nanoTime();
        private final VrCalibrationMath.StabilityWindow stability = new VrCalibrationMath.StabilityWindow();
        private int ageTicks;
        private int lastPlayerTick = Integer.MIN_VALUE;
        private int releaseTicks;
        private boolean armed;
        private boolean previousDown;
        private boolean triggerDown;
        private int detectedPresses;
        private boolean rejectedPress;
        private int problemTicks;
        private VrCalibrationMath.Problem problem = VrCalibrationMath.Problem.NONE;
        private VrCalibrationMath.Result result;
        private Component status = text("release_first", "站立并将双臂向两侧平伸成 T 字；先松开破坏/攻击扳机。菜单键取消。");

        private Session(String modelName, VrModelSettingsScreen owner, ClientLevel level, VrCalibrationMath.ModelMetrics metrics) {
            this.modelName = modelName;
            this.owner = owner;
            this.level = level;
            this.metrics = metrics;
        }
    }
}
