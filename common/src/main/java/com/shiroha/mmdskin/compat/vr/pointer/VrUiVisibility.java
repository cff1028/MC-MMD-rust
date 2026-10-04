package com.shiroha.mmdskin.compat.vr.pointer;

import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorController;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Interactive UI visibility, also available during Vivecraft's offscreen GUI pass. */
public final class VrUiVisibility {
    private static boolean initialized;
    private static Field keyboardShowing;
    private static Method radialShowing;

    private VrUiVisibility() {}

    public static boolean isUiOpen() {
        if (Minecraft.getInstance().screen != null || VrMirrorController.isEnabled()) return true;
        return isOverlayOpen();
    }

    public static boolean isOverlayOpen() {
        if (com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.isOpen()) return true;
        initialize();
        try { if (keyboardShowing != null && keyboardShowing.getBoolean(null)) return true; }
        catch (ReflectiveOperationException | LinkageError ignored) {}
        return isRadialOpen();
    }

    public static boolean isRadialOpen() {
        initialize();
        try { return radialShowing != null && (boolean)radialShowing.invoke(null); }
        catch (ReflectiveOperationException | LinkageError ignored) { return false; }
    }

    private static void initialize() {
        if (!initialized) {
            initialized = true;
            try {
                keyboardShowing = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler")
                        .getField("SHOWING");
            } catch (ReflectiveOperationException | LinkageError ignored) {}
            try {
                radialShowing = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.RadialHandler")
                        .getMethod("isShowing");
            } catch (ReflectiveOperationException | LinkageError ignored) {}
        }
    }
}
