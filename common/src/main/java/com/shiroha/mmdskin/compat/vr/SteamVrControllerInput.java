package com.shiroha.mmdskin.compat.vr;

import com.shiroha.mmdskin.ui.selector.VrControllerDebugScreen;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import java.lang.reflect.Method;
import java.nio.*;
import java.util.*;

/** Joins Vivecraft's single UpdateActionState call only while the diagnostics screen is open. */
public final class SteamVrControllerInput {
    private static NativeApi api;
    private static boolean failed, polling;
    private SteamVrControllerInput() {}

    public static void reset() { api = null; failed = false; polling = false; }

    public static Object appendActionSet(Object original) {
        polling = false;
        if (!(Minecraft.getInstance().screen instanceof VrControllerDebugScreen) || failed) return original;
        try {
            if (api == null) api = new NativeApi();
            if (api.setHandle == 0) return original;
            Object result = api.sets.append(original, api.setHandle);
            polling = true;
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            LogManager.getLogger().warn("[VR debug] Could not activate optional controller diagnostics; original input is preserved", e);
            return original;
        }
    }

    static String status() { return polling ? "live" : failed ? "error" : "debug_restart"; }

    @FunctionalInterface interface BindingLookup { List<String> get(long handle) throws ReflectiveOperationException; }
    static List<ControllerDebugSnapshot.Action> read(boolean available, BindingLookup bindings) throws ReflectiveOperationException {
        if (!polling || api == null) return List.of();
        return read(api, available, bindings);
    }
    static List<ControllerDebugSnapshot.Action> read(NativeApi api, boolean available, BindingLookup bindings) throws ReflectiveOperationException {
        List<ControllerDebugSnapshot.Action> result = new ArrayList<>();
        for (int i = 0; i < ControllerDebugBindings.CONTROLS.size(); i++) {
            var control = ControllerDebugBindings.CONTROLS.get(i);
            long handle = api.handles[i];
            List<String> bound = bindings.get(handle);
            boolean active = false, down = false; float x = 0, y = 0;
            if (available && handle != 0 && api.hands[control.hand()] != 0) {
                Object data = control.type().equals("boolean") ? api.digital : api.analog;
                Method read = control.type().equals("boolean") ? api.getDigital : api.getAnalog;
                int error = (int) read.invoke(null, handle, data, api.hands[control.hand()]);
                if (error == 0) {
                    if (control.type().equals("boolean")) { active = (boolean) api.digitalActive.invoke(data); down = active && (boolean) api.digitalDown.invoke(data); }
                    else { active = (boolean) api.analogActive.invoke(data); if (active) { x = ControllerDebugSnapshot.finite((float) api.x.invoke(data)); if (control.type().equals("vector2")) y = ControllerDebugSnapshot.finite((float) api.y.invoke(data)); } }
                } else bound = List.of("GetActionData error=" + error);
            }
            result.add(new ControllerDebugSnapshot.Action(control.name(), control.label(), control.type(), control.hand(), active, down,
                    x, y, 0, control.path(), bound));
        }
        return List.copyOf(result);
    }

    /** Owns a GC-managed struct buffer; never changes/frees Vivecraft's buffer or calls UpdateActionState itself. */
    static final class ActiveSets {
        final Method create, get, position, remaining, copy, set;
        Object buffer; int size;
        ActiveSets() throws ReflectiveOperationException {
            Class<?> struct = Class.forName("org.lwjgl.openvr.VRActiveActionSet"), array = Class.forName("org.lwjgl.openvr.VRActiveActionSet$Buffer");
            create = struct.getMethod("create", int.class); get = array.getMethod("get", int.class);
            position = array.getMethod("position"); remaining = array.getMethod("remaining");
            copy = struct.getMethod("set", struct); set = struct.getMethod("set", long.class, long.class, long.class, int.class);
        }
        Object append(Object original, long handle) throws ReflectiveOperationException {
            int count = (int) remaining.invoke(original), start = (int) position.invoke(original);
            if (buffer == null || size != count + 1) { size = count + 1; buffer = create.invoke(null, size); }
            for (int i = 0; i < count; i++) copy.invoke(get.invoke(buffer, i), get.invoke(original, start + i));
            // Vivecraft uses priority 0. Equal priority lets UI actions and debug actions observe the same input.
            set.invoke(get.invoke(buffer, count), handle, 0L, 0L, 0);
            return buffer;
        }
    }

    static final class NativeApi {
        final ActiveSets sets;
        final Method getDigital, getAnalog, digitalActive, digitalDown, analogActive, x, y;
        final Object digital, analog;
        final long setHandle;
        final long[] handles = new long[ControllerDebugBindings.CONTROLS.size()], hands = new long[2];
        NativeApi() throws ReflectiveOperationException {
            this(Class.forName("org.lwjgl.openvr.VRInput"), Class.forName("org.lwjgl.openvr.InputDigitalActionData"),
                    Class.forName("org.lwjgl.openvr.InputAnalogActionData"), new ActiveSets());
        }
        NativeApi(Class<?> input, Class<?> d, Class<?> a, ActiveSets sets) throws ReflectiveOperationException {
            this.sets = sets;
            getDigital = input.getMethod("VRInput_GetDigitalActionData", long.class, d, long.class);
            getAnalog = input.getMethod("VRInput_GetAnalogActionData", long.class, a, long.class);
            digital = d.getMethod("create").invoke(null); analog = a.getMethod("create").invoke(null);
            digitalActive = d.getMethod("bActive"); digitalDown = d.getMethod("bState"); analogActive = a.getMethod("bActive"); x = a.getMethod("x"); y = a.getMethod("y");
            LongBuffer output = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer();
            setHandle = handle(input.getMethod("VRInput_GetActionSetHandle", CharSequence.class, LongBuffer.class), ControllerDebugBindings.SET, output);
            Method getHandle = input.getMethod("VRInput_GetActionHandle", CharSequence.class, LongBuffer.class);
            Method getHand = input.getMethod("VRInput_GetInputSourceHandle", CharSequence.class, LongBuffer.class);
            for (int i = 0; i < handles.length; i++) handles[i] = handle(getHandle, ControllerDebugBindings.CONTROLS.get(i).name(), output);
            hands[0] = handle(getHand, "/user/hand/left", output); hands[1] = handle(getHand, "/user/hand/right", output);
        }
        private long handle(Method method, String path, LongBuffer buffer) throws ReflectiveOperationException {
            buffer.put(0, 0); return (int) method.invoke(null, path, buffer) == 0 ? buffer.get(0) : 0;
        }
    }
}
