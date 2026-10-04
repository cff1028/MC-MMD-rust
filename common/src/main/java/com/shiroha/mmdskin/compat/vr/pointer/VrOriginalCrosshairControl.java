package com.shiroha.mmdskin.compat.vr.pointer;

import com.shiroha.mmdskin.config.VrPointerConfigData;
import com.shiroha.mmdskin.config.VrPointerConfigManager;
import com.shiroha.mmdskin.compat.vr.VivecraftUiInteraction;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** Rendering-only policy, shared by vanilla and optional Vivecraft crosshair hooks. */
public final class VrOriginalCrosshairControl {
    private static boolean initialized;
    private static Method instance, isVrActive;

    private VrOriginalCrosshairControl() {}

    public static boolean shouldHide() {
        return shouldHide(VrPointerConfigManager.getLiveConfig(), VrOriginalCrosshairControl::isVrActive,
                VrUiVisibility::isUiOpen, VivecraftUiInteraction::isPointingAtUi);
    }

    static boolean shouldHide(VrPointerConfigData config, BooleanSupplier active, BooleanSupplier uiOpen) {
        return shouldHide(config, active, uiOpen, () -> false);
    }

    static boolean shouldHide(VrPointerConfigData config, BooleanSupplier active, BooleanSupplier uiOpen, BooleanSupplier uiHit) {
        if (config == null || config.hideCrosshairMode == null
                || config.hideCrosshairMode == VrPointerConfigData.CrosshairMode.OFF) return false;
        try {
            // Do not require an eye/world pass here: Vivecraft draws its menu
            // cursors into a separate GUI framebuffer before either eye pass.
            // Pointer visibility and controller tracking are independent settings.
            return active.getAsBoolean() && switch (config.hideCrosshairMode) {
                case ALWAYS -> true;
                case UI_ONLY -> uiOpen.getAsBoolean();
                case UI_HIT -> uiHit.getAsBoolean();
                case OFF -> false;
            };
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static boolean isVrActive() {
        try {
            if (!initialized) {
                initialized = true;
                Class<?> api = Class.forName("org.vivecraft.api.client.VRClientAPI");
                instance = api.getMethod("instance");
                isVrActive = api.getMethod("isVRActive");
            }
            if (instance == null || isVrActive == null) return false;
            Object client = instance.invoke(null);
            return client != null && (boolean)isVrActive.invoke(client);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }
}
