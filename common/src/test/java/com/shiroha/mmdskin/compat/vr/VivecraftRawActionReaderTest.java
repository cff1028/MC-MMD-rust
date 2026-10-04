package com.shiroha.mmdskin.compat.vr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VivecraftRawActionReaderTest {
    private final VivecraftRawActionReader reader = new VivecraftRawActionReader(Action.class);

    VivecraftRawActionReaderTest() throws ReflectiveOperationException {}

    @Test void calibrationAcceptsDigitalPressWithoutControllerOriginOrEnabledBinding() throws Exception {
        Action action = new Action("boolean");
        action.digitalData[0].isActive = true;
        action.digitalData[0].state = true;
        action.enabled = false;
        assertEquals(0, reader.sample(action, 0).origin());
        assertTrue(reader.anyDown(action));
        assertFalse(action.enabled, "Reading must not enable the consumed game binding");
    }

    @Test void vectorTriggerUsesAnalogSampleAndVivecraftThreshold() throws Exception {
        Action action = new Action("vector1");
        action.analogData[0].isActive = true;
        action.analogData[0].activeOrigin = 73;
        action.analogData[0].x = 0.5f;
        assertFalse(reader.anyDown(action));
        action.analogData[0].x = 0.51f;
        assertTrue(reader.anyDown(action));
        assertEquals(73, reader.sample(action, 0).origin());
        action.analogData[0].x = -0.51f;
        assertTrue(reader.anyDown(action));
    }

    @Test void actionSetSwitchIsNotAReleaseOfCapturedTrigger() throws Exception {
        for (String type : new String[] {"boolean", "vector1"}) {
            Action action = new Action(type);
            action.digitalData[0].isActive = action.digitalData[0].state = true;
            action.analogData[0].isActive = true;
            action.analogData[0].x = 1;
            assertTrue(reader.anyDown(action));
            assertFalse(reader.released(action));
            action.digitalData[0].isActive = action.digitalData[0].state = false;
            action.analogData[0].isActive = false;
            action.analogData[0].x = 0;
            assertFalse(reader.anyDown(action));
            assertFalse(reader.released(action), "Inactive INGAME while a GUI is open must retain capture");
            action.digitalData[0].isActive = action.digitalData[0].state = true;
            action.analogData[0].isActive = true;
            action.analogData[0].x = 1;
            assertFalse(reader.released(action), "Returning to the game still holding cannot leak an attack");
            action.digitalData[0].state = false;
            action.analogData[0].x = 0;
            assertTrue(reader.released(action));
        }
    }

    @Test void nativeReleaseRemainsVisibleWhileBindingIsSuppressed() throws Exception {
        for (String type : new String[] {"boolean", "vector1"}) {
            Action action = new Action(type);
            action.enabled = false;
            action.digitalData[0].isActive = action.digitalData[0].state = true;
            action.analogData[0].isActive = true;
            action.analogData[0].x = 1;
            assertTrue(reader.anyDown(action));
            assertTrue(reader.anyDown(action), "Repeated reads must not consume a physical hold");
            action.digitalData[0].state = false;
            action.analogData[0].x = 0;
            assertFalse(reader.anyDown(action), "Release permits the owner to restore the binding");
        }
    }

    @Test void nonHandedActionsNeverUseUnpopulatedSecondSample() throws Exception {
        Action action = new Action("boolean");
        action.digitalData[1].isActive = action.digitalData[1].state = true;
        assertFalse(reader.anyDown(action));
        action.handed = true;
        assertTrue(reader.anyDown(action));
        assertFalse(reader.sample(action, 0).down());
        assertTrue(reader.sample(action, 1).down());
    }

    @Test void inactiveOrOtherTypeSamplesCannotCausePhantomPresses() throws Exception {
        Action action = new Action("boolean");
        action.digitalData[0].state = true;
        action.analogData[0].isActive = true;
        action.analogData[0].x = 1;
        assertFalse(reader.anyDown(action));
        action.type = "vector1";
        action.digitalData[0].isActive = true;
        action.analogData[0].isActive = false;
        assertFalse(reader.anyDown(action));
        action.type = "pose";
        assertFalse(reader.anyDown(action));
    }

    @Test void vectorDimensionsAndNonFiniteValuesAreHandled() throws Exception {
        Action action = new Action("vector1");
        action.analogData[0].isActive = true;
        action.analogData[0].y = 1;
        assertFalse(reader.anyDown(action));
        action.type = "vector2";
        assertTrue(reader.anyDown(action));
        action.analogData[0].y = 0;
        action.analogData[0].z = 1;
        assertFalse(reader.anyDown(action));
        action.type = "vector3";
        assertTrue(reader.anyDown(action));
        action.analogData[0].z = 0;
        action.analogData[0].x = Float.NaN;
        assertFalse(reader.anyDown(action));
        action.analogData[0].x = Float.POSITIVE_INFINITY;
        assertFalse(reader.anyDown(action));
    }

    public static final class Action {
        public String type;
        public final Digital[] digitalData = {new Digital(), new Digital()};
        public final Analog[] analogData = {new Analog(), new Analog()};
        public boolean enabled = true;
        boolean handed;
        Action(String type) { this.type = type; }
        public boolean isHanded() { return handed; }
    }

    public static final class Digital {
        public boolean isActive, state;
        public long activeOrigin;
    }

    public static final class Analog {
        public boolean isActive;
        public float x, y, z;
        public long activeOrigin;
    }
}
