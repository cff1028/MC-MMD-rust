package com.shiroha.mmdskin.compat.vr;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class VivecraftPointerInputTest {
    public enum Hand { RIGHT, LEFT }
    public static class Provider {
        final Map<String, VivecraftRawActionReaderTest.Action> actions = new HashMap<>();
        public Object getInputAction(String name) { return actions.get(name); }
    }
    public static class OpenVr extends Provider {
        private Hand getOriginControllerType(long origin) { return origin == 100 ? Hand.RIGHT : origin == 200 ? Hand.LEFT : null; }
    }
    private VivecraftPointerInput.Access access() throws Exception {
        return new VivecraftPointerInput.Access(Provider.class, OpenVr.class, VivecraftRawActionReaderTest.Action.class);
    }
    private VivecraftRawActionReaderTest.Action digital(boolean handed, int hand, long origin) {
        var action = new VivecraftRawActionReaderTest.Action("boolean");
        action.handed = handed;
        action.enabled = false;
        action.digitalData[hand].isActive = true;
        action.digitalData[hand].state = true;
        action.digitalData[hand].activeOrigin = origin;
        return action;
    }

    @Test void unhandedUseAndGripAreAssignedOnlyToTheirOriginController() throws Exception {
        var provider = new OpenVr();
        var use = digital(false, 0, 200);
        var grip = digital(false, 0, 100);
        provider.actions.put("key.use", use);
        provider.actions.put("vivecraft.key.hotbarNext", grip);
        var access = access();
        for (int repeat = 0; repeat < 3; repeat++) {
            assertEquals(new VivecraftPointerInput.State(false, true), access.read(provider, 0));
            assertEquals(new VivecraftPointerInput.State(true, false), access.read(provider, 1));
        }
        assertFalse(use.enabled);
        assertFalse(grip.enabled);
        assertTrue(use.digitalData[0].state, "visual reads must not consume a held action");
        assertTrue(grip.digitalData[0].state);
        use.digitalData[0].isActive = false;
        assertFalse(access.read(provider, 1).triggerDown(), "old action-set data is not an active press");
    }

    @Test void keyboardHandedClickReadsBothSlotsWithoutChangingHandOrBinding() throws Exception {
        var provider = new OpenVr();
        var keyboard = digital(true, 1, 0);
        provider.actions.put("vivecraft.key.keyboardClick", keyboard);
        assertFalse(access().read(provider, 0).triggerDown());
        assertTrue(access().read(provider, 1).triggerDown());
        assertFalse(keyboard.enabled);
        assertTrue(keyboard.digitalData[1].state);
    }

    @Test void unknownOriginDoesNotLightBothHandsAndOtherProvidersAreSafe() throws Exception {
        var provider = new OpenVr();
        var attack = digital(false, 0, 0);
        provider.actions.put("key.attack", attack);
        assertFalse(access().read(provider, 0).triggerDown());
        assertFalse(access().read(provider, 1).triggerDown());
        attack.digitalData[0].activeOrigin = 300;
        assertFalse(access().read(provider, 0).triggerDown());
        var other = new Provider();other.actions.put("key.attack", attack);
        assertFalse(access().read(other, 0).triggerDown());
    }
}
