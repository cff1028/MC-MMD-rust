package com.shiroha.mmdskin.compat.vr;

import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorController;
import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.config.ModelConfigManager;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;
import com.shiroha.mmdskin.ui.selector.VrModelSettingsScreen;
import com.shiroha.mmdskin.ui.selector.VrHandSettingsScreen;
import com.shiroha.mmdskin.ui.selector.VrPointerSettingsScreen;
import com.shiroha.mmdskin.ui.wheel.ConfigWheelScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import java.util.function.Supplier;

/** Actions invoked by Vivecraft's radial page, applied after its input callback completes. */
public final class VrLinkedActions {
    private static Runnable pendingAction;
    private static Supplier<Screen> vrSettingsScreenFactory;
    private VrLinkedActions() {}

    public static boolean isMirrorEnabled() { return VrMirrorController.isEnabled(); }

    public static void toggleMirror() {
        pendingAction = () -> {
            if (VrMirrorController.isEnabled() || currentModelName() != null) VrMirrorController.toggle();
            else showMissingModel();
        };
    }

    public static void openModelSettings() {
        pendingAction = () -> {
            String name = currentModelName();
            if (name == null) showMissingModel();
            else Minecraft.getInstance().setScreen(new VrModelSettingsScreen(name, null));
        };
    }

    public static void openMmdWheel() {
        pendingAction = () -> Minecraft.getInstance().setScreen(new ConfigWheelScreen(null));
    }

    public static boolean isHandTrackingEnabled() {
        String name = currentModelName();
        return name == null || ModelConfigManager.getLiveConfig(name).vrFingerTrackingEnabled;
    }

    public static void toggleHandTracking() {
        pendingAction = () -> {
            String name = currentModelName();
            if (name == null) { showMissingModel(); return; }
            var config = ModelConfigManager.getConfig(name);
            config.vrFingerTrackingEnabled = !config.vrFingerTrackingEnabled;
            ModelConfigManager.saveConfig(name, config);
        };
    }

    public static void openHandSettings() {
        pendingAction = () -> {
            String name = currentModelName();
            if (name == null) showMissingModel();
            else Minecraft.getInstance().setScreen(new VrHandSettingsScreen(name, null));
        };
    }

    public static void openPointerSettings() {
        pendingAction = () -> Minecraft.getInstance().setScreen(new VrPointerSettingsScreen(null));
    }

    public static void setVrSettingsScreenFactory(Supplier<Screen> factory) { vrSettingsScreenFactory = factory; }

    public static void openVrSettings() { pendingAction = VrLinkedActions::showVrSettings; }

    public static void showVrSettings() {
        if (vrSettingsScreenFactory != null) Minecraft.getInstance().setScreen(vrSettingsScreenFactory.get());
    }

    public static String currentModelName() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        String name = PlayerModelSyncManager.getPlayerModel(player.getUUID(), player.getName().getString(), true);
        return name == null || name.isBlank() || name.equals(UIConstants.DEFAULT_MODEL_NAME)
                || name.equalsIgnoreCase("VanillaModel") || name.equalsIgnoreCase("vanilla")
                || name.equalsIgnoreCase("VanilaModel") || name.equalsIgnoreCase("vanila") ? null : name;
    }

    public static void tick() {
        VrCalibrationController.tick();
        VrMirrorController.tick();
        if (VrCalibrationController.isActive()) {
            pendingAction = null;
            return;
        }
        Runnable action = pendingAction;
        pendingAction = null;
        Minecraft mc = Minecraft.getInstance();
        if (action != null && mc.player != null && mc.level != null) action.run();
    }

    private static void showMissingModel() {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatableWithFallback(
                "message.mmdskin.vr_mirror.select_model", "请先在 MMD 轮盘中选择一个模型。"), true);
    }
}
