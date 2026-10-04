package com.shiroha.mmdskin.compat.vr;

import java.lang.reflect.Method;

/** Read-only adapter: observing a visual pointer must never capture or consume a game action. */
public final class VivecraftPointerInput {
    public record State(boolean triggerDown, boolean gripDown) {
        private static final State UP = new State(false, false);
    }
    private static boolean initialized;
    private static Access access;
    private VivecraftPointerInput() {}

    public static State read(Object provider, int hand) {
        if (provider == null || hand < 0 || hand > 1) return State.UP;
        try {
            if (!initialized) {
                initialized = true;
                access = new Access(Class.forName("org.vivecraft.client_vr.provider.MCVR"),
                        Class.forName("org.vivecraft.client_vr.provider.openvr_lwjgl.MCOpenVR"),
                        Class.forName("org.vivecraft.client_vr.provider.openvr_lwjgl.VRInputAction"));
            }
            State fallback = access == null ? State.UP : access.read(provider, hand);
            int physical = VivecraftUiInteraction.physicalHand(hand);
            var trigger = SteamVrUiInput.trigger(physical);
            var grip = SteamVrUiInput.grip(physical);
            return new State(trigger.active() ? trigger.down() : fallback.triggerDown(),
                    grip.active() ? grip.down() : fallback.gripDown());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return State.UP;
        }
    }

    static final class Access {
        private final Method inputAction, originController;
        private final VivecraftRawActionReader raw;

        Access(Class<?> providerClass, Class<?> openVrClass, Class<?> actionClass) throws ReflectiveOperationException {
            inputAction = providerClass.getMethod("getInputAction", String.class);
            originController = openVrClass.getDeclaredMethod("getOriginControllerType", long.class);
            originController.setAccessible(true);
            raw = new VivecraftRawActionReader(actionClass);
        }

        State read(Object provider, int hand) throws ReflectiveOperationException {
            boolean trigger = down(provider, "vivecraft.key.guiLeftClick", hand)
                    || down(provider, "vivecraft.key.keyboardClick", hand)
                    || down(provider, "key.attack", hand) || down(provider, "key.use", hand);
            boolean grip = down(provider, "vivecraft.key.hotbarNext", hand)
                    || down(provider, "vivecraft.key.hotbarPrev", hand);
            return new State(trigger, grip);
        }

        private boolean down(Object provider, String name, int hand) throws ReflectiveOperationException {
            Object action = inputAction.invoke(provider, name);
            if (action == null) return false;
            if (raw.isHanded(action)) return raw.sample(action, hand).down();
            VivecraftRawActionReader.Sample sample = raw.sample(action, 0);
            // Non-handed actions populate sample zero only. The origin identifies
            // their actual controller; copying sample zero to both hands is wrong.
            if (!sample.down() || sample.origin() == 0
                    || !originController.getDeclaringClass().isInstance(provider)) return false;
            Object origin = originController.invoke(provider, sample.origin());
            return origin instanceof Enum<?> controller && controller.ordinal() == hand;
        }
    }
}
