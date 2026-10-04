package com.shiroha.mmdskin.compat.vr;

import com.shiroha.mmdskin.ui.spatial.SpatialMenuHost;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;

/** Reads one existing OpenVR action-state update; never starts or independently polls the VR runtime. */
public final class SteamVrMenuInput {
    public record Stick(boolean active, float x, float y) { public static final Stick ZERO = new Stick(false, 0, 0); }
    private static final SteamVrUiInput.Button[] buttons = new SteamVrUiInput.Button[4];
    private static Stick stick = Stick.ZERO;
    private static Api api;
    private static boolean failed, polling, controls;
    private static long sampledAt;
    static { clear(); }
    private SteamVrMenuInput() {}

    public static void reset() { api = null; failed = polling = controls = false; clear(); }
    private static void clear() { Arrays.fill(buttons, SteamVrUiInput.Button.UP); stick = Stick.ZERO; sampledAt = 0; }

    public static Object appendActionSet(Object original) {
        polling = controls = false;
        if (failed) { clear(); return original; }
        try {
            if (api == null) api = new Api();
            if (api.openSet == 0) { clear(); return original; }
            Object result = api.openSets.append(original, api.openSet);
            polling = true;
            if (SpatialMenuHost.isOpen() && api.controlSet != 0) {
                result = api.controlSets.append(result, api.controlSet); controls = true;
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true; clear();
            LogManager.getLogger().warn("[VR menu] Physical menu input unavailable; original Vivecraft controls remain available", e);
            return original;
        }
    }

    public static void poll() {
        if (!polling || api == null) { clear(); return; }
        try {
            if (!(boolean)api.available.invoke(null)) { clear(); return; }
            long now = System.nanoTime(); stick = Stick.ZERO;
            if (api.stickAction != 0 && api.hands[1] != 0
                    && (int)api.readAnalog.invoke(null, api.stickAction, api.analog, api.hands[1]) == 0
                    && (boolean)api.analogActive.invoke(api.analog)) {
                stick = new Stick(true, finiteAxis((float)api.x.invoke(api.analog)), finiteAxis((float)api.y.invoke(api.analog)));
            }
            Arrays.fill(buttons, SteamVrUiInput.Button.UP);
            if (controls) for (int i = 0; i < buttons.length; i++) {
                if (api.actions[i] == 0 || api.hands[i % 2] == 0) continue;
                if ((int)api.readDigital.invoke(null, api.actions[i], api.digital, api.hands[i % 2]) == 0
                        && (boolean)api.digitalActive.invoke(api.digital)) {
                    float age = (float)api.time.invoke(api.digital);
                    buttons[i] = new SteamVrUiInput.Button(true, (boolean)api.down.invoke(api.digital),
                            now + (long)(Math.clamp(Float.isFinite(age) ? age : 0, -60f, 0f) * 1_000_000_000L));
                }
            }
            sampledAt = now;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { clear(); }
    }

    public static Stick rightStick() { return fresh() ? stick : Stick.ZERO; }
    public static SteamVrUiInput.Button trigger(int physicalHand) { return sample(physicalHand); }
    public static SteamVrUiInput.Button grip(int physicalHand) { return sample(physicalHand + 2); }
    private static SteamVrUiInput.Button sample(int index) {
        return index >= 0 && index < buttons.length && fresh() ? buttons[index] : SteamVrUiInput.Button.UP;
    }
    private static boolean fresh() { return sampledAt != 0 && System.nanoTime() - sampledAt < 500_000_000L; }
    private static float finiteAxis(float value) { return Float.isFinite(value) ? Math.clamp(value, -1, 1) : 0; }

    private static final class Api {
        final SteamVrControllerInput.ActiveSets openSets = new SteamVrControllerInput.ActiveSets(), controlSets = new SteamVrControllerInput.ActiveSets();
        final Method readDigital, readAnalog, digitalActive, analogActive, down, time, x, y, available;
        final Object digital, analog;
        final long openSet, controlSet, stickAction;
        final long[] actions = new long[4], hands = new long[2];
        Api() throws ReflectiveOperationException {
            Class<?> input = Class.forName("org.lwjgl.openvr.VRInput"), d = Class.forName("org.lwjgl.openvr.InputDigitalActionData"), a = Class.forName("org.lwjgl.openvr.InputAnalogActionData");
            digital = d.getMethod("create").invoke(null); analog = a.getMethod("create").invoke(null);
            readDigital = input.getMethod("VRInput_GetDigitalActionData", long.class, d, long.class);
            readAnalog = input.getMethod("VRInput_GetAnalogActionData", long.class, a, long.class);
            digitalActive = d.getMethod("bActive"); analogActive = a.getMethod("bActive"); down = d.getMethod("bState"); time = d.getMethod("fUpdateTime");
            x = a.getMethod("x"); y = a.getMethod("y"); available = Class.forName("org.lwjgl.openvr.VRSystem").getMethod("VRSystem_IsInputAvailable");
            LongBuffer result = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer();
            openSet = handle(input, "VRInput_GetActionSetHandle", VrMenuActionBindings.OPEN_SET, result);
            controlSet = handle(input, "VRInput_GetActionSetHandle", VrMenuActionBindings.CONTROL_SET, result);
            stickAction = handle(input, "VRInput_GetActionHandle", VrMenuActionBindings.STICK, result);
            for (int i = 0; i < actions.length; i++) actions[i] = handle(input, "VRInput_GetActionHandle", VrMenuActionBindings.CONTROL_SET + "/in/" + VrMenuActionBindings.BUTTONS.get(i), result);
            hands[0] = handle(input, "VRInput_GetInputSourceHandle", "/user/hand/left", result);
            hands[1] = handle(input, "VRInput_GetInputSourceHandle", "/user/hand/right", result);
        }
        private static long handle(Class<?> input, String method, String path, LongBuffer result) throws ReflectiveOperationException {
            result.put(0, 0); return (int)input.getMethod(method, CharSequence.class, LongBuffer.class).invoke(null, path, result) == 0 ? result.get(0) : 0;
        }
    }
}
