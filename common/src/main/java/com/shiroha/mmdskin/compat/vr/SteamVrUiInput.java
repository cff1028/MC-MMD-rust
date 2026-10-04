package com.shiroha.mmdskin.compat.vr;

import com.shiroha.mmdskin.compat.vr.keyboard.VivecraftKeyboardBridge;
import com.shiroha.mmdskin.compat.vr.pointer.VrUiVisibility;
import com.shiroha.mmdskin.config.VrPointerConfigManager;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import java.lang.reflect.Method;
import java.nio.*;

/** Four physical UI actions sampled after Vivecraft's sole UpdateActionState, never game action aliases. */
public final class SteamVrUiInput {
    public record Button(boolean active, boolean down, long changedAt) {
        public static final Button UP = new Button(false, false, 0);
    }
    private static final Button[] buttons = {Button.UP, Button.UP, Button.UP, Button.UP};
    private static Api api;
    private static boolean polling, failed;
    private static long sampledAt;
    private SteamVrUiInput() {}
    public static void reset() { api = null; polling = failed = false; clear(); }
    private static void clear() { java.util.Arrays.fill(buttons, Button.UP); sampledAt = 0; }
    public static boolean wantsInput() {
        return Minecraft.getInstance().screen != null && VrPointerConfigManager.getLiveConfig().leftTriggerInteraction
                || VrUiVisibility.isRadialOpen() || VivecraftKeyboardBridge.isShowing();
    }
    public static Object appendActionSet(Object original) {
        polling = false;
        if (failed || !wantsInput()) { clear(); return original; }
        try {
            if (api == null) api = new Api();
            if (api.set == 0) return original;
            Object result = api.sets.append(original, api.set);
            polling = true; return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true; clear(); LogManager.getLogger().warn("[VR UI] Independent controller input is unavailable; preserving original controls", e); return original;
        }
    }
    public static void poll() {
        if (!polling || api == null) { clear(); return; }
        try {
            if (!(boolean) api.available.invoke(null)) { clear(); return; }
            long now = System.nanoTime();
            for (int i = 0; i < buttons.length; i++) {
                buttons[i] = Button.UP;
                if (api.actions[i] == 0 || api.hands[i % 2] == 0) continue;
                int error = (int) api.read.invoke(null, api.actions[i], api.data, api.hands[i % 2]);
                if (error == 0 && (boolean) api.active.invoke(api.data)) {
                    float age = (float) api.time.invoke(api.data);
                    buttons[i] = new Button(true, (boolean) api.down.invoke(api.data),
                            now + (long) (Math.clamp(Float.isFinite(age) ? age : 0, -60f, 0f) * 1_000_000_000L));
                }
            }
            sampledAt = now;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { clear(); }
    }
    public static Button trigger(int physicalHand) { return sample(physicalHand); }
    public static Button grip(int physicalHand) { return sample(physicalHand + 2); }
    private static Button sample(int index) {
        return index >= 0 && index < 4 && sampledAt != 0 && System.nanoTime() - sampledAt < 500_000_000L ? buttons[index] : Button.UP;
    }
    static final class Api {
        final SteamVrControllerInput.ActiveSets sets = new SteamVrControllerInput.ActiveSets();
        final Method read, active, down, time, available;
        final Object data;
        final long set;
        final long[] actions = new long[4], hands = new long[2];
        Api() throws ReflectiveOperationException {
            Class<?> input = Class.forName("org.lwjgl.openvr.VRInput"), digital = Class.forName("org.lwjgl.openvr.InputDigitalActionData");
            data = digital.getMethod("create").invoke(null);
            read = input.getMethod("VRInput_GetDigitalActionData", long.class, digital, long.class);
            active = digital.getMethod("bActive"); down = digital.getMethod("bState"); time = digital.getMethod("fUpdateTime");
            available = Class.forName("org.lwjgl.openvr.VRSystem").getMethod("VRSystem_IsInputAvailable");
            LongBuffer result = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer();
            set = handle(input, "VRInput_GetActionSetHandle", VrUiActionBindings.SET, result);
            for (int i = 0; i < 4; i++) actions[i] = handle(input, "VRInput_GetActionHandle", VrUiActionBindings.SET + "/in/" + VrUiActionBindings.NAMES.get(i), result);
            hands[0] = handle(input, "VRInput_GetInputSourceHandle", "/user/hand/left", result);
            hands[1] = handle(input, "VRInput_GetInputSourceHandle", "/user/hand/right", result);
        }
        private static long handle(Class<?> api, String method, String path, LongBuffer result) throws ReflectiveOperationException {
            result.put(0, 0); return (int) api.getMethod(method, CharSequence.class, LongBuffer.class).invoke(null, path, result) == 0 ? result.get(0) : 0;
        }
    }
}
