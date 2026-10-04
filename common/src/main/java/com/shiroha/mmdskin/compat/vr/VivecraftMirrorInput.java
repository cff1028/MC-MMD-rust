package com.shiroha.mmdskin.compat.vr;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Controller poses and raw Vivecraft actions, with scoped ownership of consumed input. */
public final class VivecraftMirrorInput {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String NEXT = "vivecraft.key.hotbarNext";
    private static final String PREVIOUS = "vivecraft.key.hotbarPrev";
    private static final String ATTACK = "key.attack";
    private static final String GUI_CLICK = "vivecraft.key.guiLeftClick";
    private static final Object LEGACY_MIRROR_OWNER = new Object();
    private static final Map<Object, Capture> owners = new IdentityHashMap<>();
    private static final Map<String, SuppressedAction> suppressed = new HashMap<>();
    private static Object priorityProvider;
    private static volatile boolean initialized;
    private static Bindings bindings;
    private static boolean failureLogged;

    private VivecraftMirrorInput() {}

    /** 0 is main hand and 1 is off hand, including Vivecraft's reverse-hands mapping. */
    public static ControllerPose readController(int hand) {
        if (hand < 0 || hand > 1) return null;
        Bindings api = getBindings();
        if (api == null) return null;
        try {
            Object client = api.clientInstance.invoke(null);
            if (client == null || !(boolean) api.isVrActive.invoke(client)) return null;
            Object provider = api.providerInstance.invoke(null);
            if (provider == null || !(boolean) api.isControllerTracking.invoke(provider, hand)) return null;
            Object pose = api.worldRenderPose.invoke(client);
            if (pose == null) return null;
            Object part = (hand == 0 ? api.mainHand : api.offHand).invoke(pose);
            if (part == null) return null;
            Object rawPosition = api.position.invoke(part);
            Object rawRotation = api.rotation.invoke(part);
            if (!(rawPosition instanceof Vec3 position) || !(rawRotation instanceof Quaternionfc rotation)
                    || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                    || !Float.isFinite(rotation.x()) || !Float.isFinite(rotation.y())
                    || !Float.isFinite(rotation.z()) || !Float.isFinite(rotation.w())) return null;
            Quaternionf orientation = new Quaternionf(rotation);
            if (!Float.isFinite(orientation.lengthSquared()) || orientation.lengthSquared() < 1.0e-6f) return null;
            orientation.normalize();
            // These actions are not handed: digitalData[1] is not an off-hand sample.
            // Match activeOrigin to the tracked device instead of assuming a hand.
            boolean grip = api.actionDown(provider, NEXT, hand) || api.actionDown(provider, PREVIOUS, hand);
            return new ControllerPose(position, orientation, grip);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logFailure(failure);
            return null;
        }
    }

    /** Raw trigger state remains readable while an input owner suppresses its game action. */
    public static boolean readTrigger(int hand) {
        if (hand < 0 || hand > 1) return false;
        Bindings api = getBindings();
        if (api == null) return false;
        try {
            Object client = api.clientInstance.invoke(null);
            if (client == null || !(boolean) api.isVrActive.invoke(client)) return false;
            Object provider = api.providerInstance.invoke(null);
            if (provider == null) return false;
            // GUI action sets replace INGAME while a Screen is present.
            return api.actionDown(provider, Minecraft.getInstance().screen == null ? ATTACK : GUI_CLICK, hand);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logFailure(failure);
            return false;
        }
    }

    /** Calibration accepts either attack source; it does not need a tracked-hand origin. */
    public static boolean readAnyTrigger() {
        Bindings api = getBindings();
        if (api == null) return false;
        try {
            Object client = api.clientInstance.invoke(null);
            if (client == null || !(boolean) api.isVrActive.invoke(client)) return false;
            Object provider = api.providerInstance.invoke(null);
            if (provider == null) return false;
            String action = Minecraft.getInstance().screen == null ? ATTACK : GUI_CLICK;
            return api.rawActions.anyDown(api.inputAction.invoke(provider, action));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logFailure(failure);
            return false;
        }
    }

    public record ControllerPose(Vec3 position, Quaternionf rotation, boolean gripDown) {}

    public static void setMirrorInteractionPriority(boolean active) {
        setInteractionPriority(LEGACY_MIRROR_OWNER, active, false);
    }

    /** Independent owners cannot accidentally release another tool's input capture. */
    public static void setInteractionPriority(Object owner, boolean hotbar, boolean trigger) {
        if (owner == null) throw new IllegalArgumentException("Input owner cannot be null");
        if (hotbar || trigger) owners.put(owner, new Capture(hotbar, trigger));
        else owners.remove(owner);
        refreshPriorities();
    }

    public static boolean isTriggerCapturedByOther(Object owner) {
        return owners.entrySet().stream().anyMatch(entry -> entry.getKey() != owner && entry.getValue().trigger());
    }

    /** Called before Vivecraft consumes actions, also completing release-after-button-up. */
    public static void refreshPriorities() {
        if (owners.isEmpty() && suppressed.isEmpty()) return;
        Bindings api = getBindings();
        if (api == null) return;
        try {
            Object provider = api.providerInstance.invoke(null);
            Object client = api.clientInstance.invoke(null);
            boolean running = client != null && (boolean) api.isVrActive.invoke(client);
            if (provider != priorityProvider) {
                restoreAll(api);
                priorityProvider = provider;
            }
            boolean hotbar = false;
            boolean trigger = false;
            for (Capture capture : owners.values()) {
                hotbar |= capture.hotbar();
                trigger |= capture.trigger();
            }
            updateCapture(api, provider, NEXT, running && hotbar, running);
            updateCapture(api, provider, PREVIOUS, running && hotbar, running);
            updateCapture(api, provider, ATTACK, running && trigger, running);
            updateCapture(api, provider, GUI_CLICK, running && trigger, running);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logFailure(failure);
        }
    }

    private static void updateCapture(Bindings api, Object provider, String name, boolean requested, boolean running)
            throws ReflectiveOperationException {
        SuppressedAction saved = suppressed.get(name);
        Object action = provider == null ? null : api.inputAction.invoke(provider, name);
        if (saved != null && saved.action() != action) {
            api.setActionEnabled.invoke(saved.action(), saved.wasEnabled());
            suppressed.remove(name);
            saved = null;
        }
        if (requested && action != null && saved == null) {
            saved = new SuppressedAction(action, (boolean) api.isActionEnabledRaw.invoke(action));
            suppressed.put(name, saved);
        }
        if (saved == null) return;
        saved.sawPress |= api.rawActions.anyDown(saved.action());
        // Keep capture until release if OFF/confirmation closes under a held trigger.
        // An inactive action set (e.g. opening a GUI) is not evidence of release.
        if (!requested && (!running || !saved.sawPress || api.rawActions.released(saved.action()))) {
            api.setActionEnabled.invoke(saved.action(), saved.wasEnabled());
            suppressed.remove(name);
            return;
        }
        api.setActionEnabled.invoke(saved.action(), false);
        api.unpress.invoke(saved.action());
        Object mapping = api.keyBinding.get(saved.action());
        if (mapping instanceof KeyMapping key) {
            key.setDown(false);
            while (key.consumeClick()) { /* Consume only this captured action. */ }
        }
        if (ATTACK.equals(name) && Minecraft.getInstance().gameMode != null) {
            Minecraft.getInstance().gameMode.stopDestroyBlock();
        }
    }

    private static void restoreAll(Bindings api) throws ReflectiveOperationException {
        var iterator = suppressed.values().iterator();
        while (iterator.hasNext()) {
            SuppressedAction saved = iterator.next();
            api.setActionEnabled.invoke(saved.action(), saved.wasEnabled());
            iterator.remove();
        }
    }

    private record Capture(boolean hotbar, boolean trigger) {}
    private static final class SuppressedAction {
        private final Object action;
        private final boolean wasEnabled;
        private boolean sawPress;

        SuppressedAction(Object action, boolean wasEnabled) {
            this.action = action;
            this.wasEnabled = wasEnabled;
        }

        Object action() { return action; }
        boolean wasEnabled() { return wasEnabled; }
    }

    private static void logFailure(Throwable failure) {
        if (!failureLogged) {
            failureLogged = true;
            LOGGER.warn("Unable to access Vivecraft mirror input", failure);
        }
    }

    private static Bindings getBindings() {
        if (!initialized) synchronized (VivecraftMirrorInput.class) {
            if (!initialized) {
                try { bindings = new Bindings(); }
                catch (ClassNotFoundException absent) { /* Vivecraft is optional. */ }
                catch (ReflectiveOperationException | LinkageError failure) { logFailure(failure); }
                initialized = true;
            }
        }
        return bindings;
    }

    private static final class Bindings {
        final Method clientInstance, isVrActive, worldRenderPose, mainHand, offHand, position, rotation;
        final Method providerInstance, isControllerTracking, inputAction, isActionEnabledRaw, setActionEnabled;
        final Method originController, unpress;
        final Field keyBinding;
        final VivecraftRawActionReader rawActions;

        Bindings() throws ReflectiveOperationException {
            Class<?> clientClass = Class.forName("org.vivecraft.api.client.VRClientAPI");
            Class<?> poseClass = Class.forName("org.vivecraft.api.data.VRPose");
            Class<?> partClass = Class.forName("org.vivecraft.api.data.VRBodyPartData");
            Class<?> providerClass = Class.forName("org.vivecraft.client_vr.provider.MCVR");
            Class<?> openVrClass = Class.forName("org.vivecraft.client_vr.provider.openvr_lwjgl.MCOpenVR");
            Class<?> actionClass = Class.forName("org.vivecraft.client_vr.provider.openvr_lwjgl.VRInputAction");
            rawActions = new VivecraftRawActionReader(actionClass);
            clientInstance = clientClass.getMethod("instance");
            isVrActive = clientClass.getMethod("isVRActive");
            worldRenderPose = clientClass.getMethod("getWorldRenderPose");
            mainHand = poseClass.getMethod("getMainHand");
            offHand = poseClass.getMethod("getOffHand");
            position = partClass.getMethod("getPos");
            rotation = partClass.getMethod("getRotation");
            providerInstance = providerClass.getMethod("get");
            isControllerTracking = providerClass.getMethod("isControllerTracking", int.class);
            inputAction = providerClass.getMethod("getInputAction", String.class);
            isActionEnabledRaw = actionClass.getMethod("isEnabledRaw");
            setActionEnabled = actionClass.getMethod("setEnabled", boolean.class);
            originController = openVrClass.getDeclaredMethod("getOriginControllerType", long.class);
            originController.setAccessible(true);
            unpress = actionClass.getMethod("unpressBindingImmediately");
            keyBinding = actionClass.getField("keyBinding");
        }

        boolean actionDown(Object provider, String name, int hand) throws ReflectiveOperationException {
            Object action = inputAction.invoke(provider, name);
            if (action == null) return false;
            if (rawActions.isHanded(action)) return rawActions.sample(action, hand).down();
            VivecraftRawActionReader.Sample sample = rawActions.sample(action, 0);
            if (!sample.down() || !originController.getDeclaringClass().isInstance(provider)) return false;
            long origin = sample.origin();
            if (origin == 0) return false;
            Object controller = originController.invoke(provider, origin);
            return controller instanceof Enum<?> source && source.ordinal() == hand;
        }
    }
}
