package com.shiroha.mmdskin.compat.vr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.*;

import static com.shiroha.mmdskin.compat.vr.ControllerDebugSnapshot.*;

/** Screen-owned, read-only inspector. Never initializes OpenVR, polls events, changes bindings,
 * calls UpdateActionState, or changes VRInputAction.currentHand. */
public final class VivecraftControllerDebug {
    private Api api;
    private Object lastProvider;
    private long retryAt;
    private String failure = "unavailable";

    public ControllerDebugSnapshot read() {
        String providerName = "Vivecraft";
        try {
            Object provider = Class.forName("org.vivecraft.client_vr.provider.MCVR").getMethod("get").invoke(null);
            if (provider == null) return unavailable("inactive", providerName);
            providerName = provider.getClass().getSimpleName();
            Class<?> openVr = Class.forName("org.vivecraft.client_vr.provider.openvr_lwjgl.MCOpenVR");
            if (!openVr.isInstance(provider)) return unavailable("unsupported", providerName);
            if (!(boolean) Class.forName("org.vivecraft.client_vr.VRState").getField("VR_RUNNING").get(null)) return unavailable("inactive", providerName);
            Field initialized = openVr.getDeclaredField("inputInitialized"); initialized.setAccessible(true);
            if (!initialized.getBoolean(provider)) return unavailable("inactive", providerName);
            long now = System.nanoTime();
            if (api == null && now < retryAt) return unavailable(failure, providerName);
            if (api == null || provider != lastProvider) { api = new Api(); lastProvider = provider; }
            return api.read(provider, providerName, now);
        } catch (ClassNotFoundException e) { return unavailable("missing", providerName); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            api = null; retryAt = System.nanoTime() + 2_000_000_000L;
            failure = "error";
            return unavailable(failure, providerName);
        }
    }

    static final class Actions {
        final VivecraftRawActionReader raw;
        final Field name, type, binding, handle, analog;
        final java.util.function.Function<Object, String> keyName;
        final Field x, y, z;
        Actions(Class<?> cls) throws ReflectiveOperationException {
            this(cls, value -> ((net.minecraft.client.KeyMapping) value).getName());
        }
        Actions(Class<?> cls, java.util.function.Function<Object, String> keyName) throws ReflectiveOperationException {
            this.keyName = keyName;
            raw = new VivecraftRawActionReader(cls);
            name = cls.getField("name"); type = cls.getField("type"); binding = cls.getField("keyBinding"); handle = cls.getField("handle");
            analog = cls.getField("analogData");
            Class<?> data = analog.getType().getComponentType();
            x = data.getField("x"); y = data.getField("y"); z = data.getField("z");
        }
        Action read(Object action, int sampleIndex, int hand, String origin, List<String> bindings) throws ReflectiveOperationException {
            var sample = raw.sample(action, sampleIndex);
            String kind = (String) type.get(action);
            float ax = 0, ay = 0, az = 0;
            if (sample.active() && kind.startsWith("vector")) {
                Object data = ((Object[]) analog.get(action))[sampleIndex];
                ax = finite(x.getFloat(data));
                if (!kind.equals("vector1")) ay = finite(y.getFloat(data));
                if (kind.equals("vector3")) az = finite(z.getFloat(data));
            }
            return new Action((String) name.get(action), keyName.apply(binding.get(action)), kind,
                    hand, sample.active(), sample.down(), ax, ay, az, origin, bindings);
        }
    }

    private static final class Api {
        final Method getActions, role, connected, state, integer, string, buttonName, inputAvailable;
        final Method pressed, touched, axis, axisX, axisY, bindingInfo, bindingGet, devicePath, inputPath, mode, slot;
        final Method getOrigin, originDevice, originComponent, localized;
        final Object controllerState, origins, bindings;
        final IntBuffer error = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        final IntBuffer count = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        final ByteBuffer label = ByteBuffer.allocateDirect(512);
        final Actions actions;
        final Field vrSettings, reverseHands;
        final Method holder;
        final Map<Long, List<String>> bindingCache = new HashMap<>();
        final Map<Long, Origin> originCache = new HashMap<>();
        final Map<Integer, Metadata> devices = new HashMap<>();
        long refreshAt;
        int bindingBudget;

        Api() throws ReflectiveOperationException {
            Class<?> system = Class.forName("org.lwjgl.openvr.VRSystem"), input = Class.forName("org.lwjgl.openvr.VRInput");
            Class<?> stateClass = Class.forName("org.lwjgl.openvr.VRControllerState"), axisClass = Class.forName("org.lwjgl.openvr.VRControllerAxis");
            Class<?> originClass = Class.forName("org.lwjgl.openvr.InputOriginInfo"), infoClass = Class.forName("org.lwjgl.openvr.InputBindingInfo");
            Class<?> bufferClass = Class.forName("org.lwjgl.openvr.InputBindingInfo$Buffer");
            getActions = Class.forName("org.vivecraft.client_vr.provider.MCVR").getMethod("getInputActions");
            role = system.getMethod("VRSystem_GetTrackedDeviceIndexForControllerRole", int.class);
            connected = system.getMethod("VRSystem_IsTrackedDeviceConnected", int.class);
            inputAvailable = system.getMethod("VRSystem_IsInputAvailable");
            state = system.getMethod("VRSystem_GetControllerState", int.class, stateClass);
            integer = system.getMethod("VRSystem_GetInt32TrackedDeviceProperty", int.class, int.class, IntBuffer.class);
            string = system.getMethod("VRSystem_GetStringTrackedDeviceProperty", int.class, int.class, IntBuffer.class);
            buttonName = system.getMethod("VRSystem_GetButtonIdNameFromEnum", int.class);
            controllerState = stateClass.getMethod("create").invoke(null);
            pressed = stateClass.getMethod("ulButtonPressed"); touched = stateClass.getMethod("ulButtonTouched");
            axis = stateClass.getMethod("rAxis", int.class); axisX = axisClass.getMethod("x"); axisY = axisClass.getMethod("y");
            origins = originClass.getMethod("create").invoke(null);
            getOrigin = input.getMethod("VRInput_GetOriginTrackedDeviceInfo", long.class, originClass);
            originDevice = originClass.getMethod("trackedDeviceIndex"); originComponent = originClass.getMethod("rchRenderModelComponentNameString");
            localized = input.getMethod("VRInput_GetOriginLocalizedName", long.class, ByteBuffer.class, int.class);
            bindings = infoClass.getMethod("create", int.class).invoke(null, 32);
            bindingInfo = input.getMethod("VRInput_GetActionBindingInfo", long.class, bufferClass, IntBuffer.class);
            bindingGet = bufferClass.getMethod("get", int.class);
            devicePath = infoClass.getMethod("rchDevicePathNameString"); inputPath = infoClass.getMethod("rchInputPathNameString");
            mode = infoClass.getMethod("rchModeNameString"); slot = infoClass.getMethod("rchSlotNameString");
            actions = new Actions(Class.forName("org.vivecraft.client_vr.provider.openvr_lwjgl.VRInputAction"));
            Class<?> data = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR"); holder = data.getMethod("getInstance");
            vrSettings = data.getField("vrSettings"); reverseHands = vrSettings.getType().getField("reverseHands");
        }

        ControllerDebugSnapshot read(Object provider, String providerName, long now) throws ReflectiveOperationException {
            if (now >= refreshAt) { bindingCache.clear(); originCache.clear(); devices.clear(); refreshAt = now + 2_000_000_000L; }
            bindingBudget = 8; // Bound IPC work per frame even with hundreds of mod-added actions.
            boolean available = (boolean) inputAvailable.invoke(null);
            int left = (int) role.invoke(null, 1), right = (int) role.invoke(null, 2);
            List<Hand> hands = List.of(hand(0, left, available), hand(1, right, available));
            boolean reversed = reverseHands.getBoolean(vrSettings.get(holder.invoke(null)));
            List<Action> values = new ArrayList<>();
            List<Action> physical = SteamVrControllerInput.read(available, this::bindings);
            values.addAll(physical);
            for (Object action : (Collection<?>) getActions.invoke(provider)) {
                String type = (String) actions.type.get(action);
                if (!List.of("boolean", "vector1", "vector2", "vector3").contains(type)) continue;
                long handle = actions.handle.getLong(action);
                List<String> bound = bindings(handle);
                boolean handed = actions.raw.isHanded(action);
                for (int sample = 0; sample < (handed ? 2 : 1); sample++) {
                    var raw = actions.raw.sample(action, sample);
                    Origin origin = origin(raw.origin());
                    int side = origin.device >= 0 && origin.device == left ? 0 : origin.device >= 0 && origin.device == right ? 1
                            : handed ? physicalHand(sample, reversed) : -1;
                    Action value = actions.read(action, sample, side, origin.label, bound);
                    // SteamVR dashboard/focus loss must never leave a stale pressed action on screen.
                    if (!available) value = new Action(value.name(), value.key(), value.type(), side, false, false, 0, 0, 0, value.origin(), bound);
                    values.add(value);
                }
            }
            values.sort(Comparator.comparing(Action::name).thenComparingInt(Action::hand));
            String status = SteamVrControllerInput.status();
            if (status.equals("live") && physical.stream().noneMatch(Action::active)) status = "debug_unbound";
            return new ControllerDebugSnapshot(available ? status : "input_unavailable", providerName, hands, values);
        }

        Hand hand(int side, int device, boolean input) throws ReflectiveOperationException {
            if (device < 0 || device >= 64 || !(boolean) connected.invoke(null, device)) return new Hand(side, device, "", false, false, 0, 0, List.of(), List.of());
            Metadata meta = devices.get(device);
            if (meta == null) {
                error.put(0, 0); String model = (String) string.invoke(null, device, 1001, error); // Prop_ModelNumber_String
                if (error.get(0) != 0) model = "device " + device;
                int[] types = new int[5];
                for (int i = 0; i < 5; i++) { error.put(0, 0); types[i] = (int) integer.invoke(null, device, 3002 + i, error); if (error.get(0) != 0) types[i] = 0; }
                meta = new Metadata(model, types); devices.put(device, meta);
            }
            boolean valid = input && (boolean) state.invoke(null, device, controllerState);
            if (!valid) return new Hand(side, device, meta.model, true, false, 0, 0, List.of(), List.of());
            long down = (long) pressed.invoke(controllerState), touch = (long) touched.invoke(controllerState);
            List<Axis> axes = new ArrayList<>(); List<String> buttons = new ArrayList<>();
            for (int i = 0; i < 64; i++) if (bit(down | touch, i)) {
                String name = (String) buttonName.invoke(null, i);
                buttons.add("#" + i + " " + name + (bit(down, i) ? " [press]" : "") + (bit(touch, i) ? " [touch]" : ""));
            }
            for (int i = 0; i < 5; i++) {
                Object value = axis.invoke(controllerState, i);
                axes.add(new Axis(i, meta.types[i], finite((float) axisX.invoke(value)), finite((float) axisY.invoke(value))));
            }
            return new Hand(side, device, meta.model, true, true, down, touch, axes, buttons);
        }

        List<String> bindings(long handle) throws ReflectiveOperationException {
            if (handle == 0) return List.of();
            if (bindingCache.containsKey(handle)) return bindingCache.get(handle);
            if (bindingBudget-- <= 0) return List.of("[pending binding lookup]");
            count.put(0, 0); int result = (int) bindingInfo.invoke(null, handle, bindings, count);
            List<String> rows = new ArrayList<>();
            if (result == 0) for (int i = 0; i < Math.min(32, count.get(0)); i++) {
                Object info = bindingGet.invoke(bindings, i);
                rows.add(devicePath.invoke(info) + " " + inputPath.invoke(info) + " mode=" + mode.invoke(info) + " slot=" + slot.invoke(info));
            }
            if (result != 0) rows.add("GetActionBindingInfo error=" + result);
            if (count.get(0) > 32) rows.add("[bindings truncated at 32]");
            List<String> value = List.copyOf(rows); bindingCache.put(handle, value); return value;
        }
        Origin origin(long handle) throws ReflectiveOperationException {
            if (handle == 0) return new Origin(-1, "");
            Origin result = originCache.get(handle);
            if (result == null) {
                int device = -1; String name = "origin=" + Long.toUnsignedString(handle);
                if ((int) getOrigin.invoke(null, handle, origins) == 0) { device = (int) originDevice.invoke(origins); name = (String) originComponent.invoke(origins); }
                label.clear();
                if ((int) localized.invoke(null, handle, label, 7) == 0) {
                    int length = 0; while (length < label.capacity() && label.get(length) != 0) length++;
                    byte[] utf8 = new byte[length]; label.get(0, utf8); name = new String(utf8, java.nio.charset.StandardCharsets.UTF_8);
                }
                result = new Origin(device, name); originCache.put(handle, result);
            }
            return result;
        }
        record Metadata(String model, int[] types) {}
        record Origin(int device, String label) {}
    }
}
