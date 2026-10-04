package com.shiroha.mmdskin.compat.vr;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VivecraftControllerDebugTest {
    @Test void inspectionPreservesBindingHandAndDoesNotConsumePressedSamples() throws Exception {
        FakeAction value = new FakeAction();
        value.digitalData[1].isActive = value.digitalData[1].state = true;
        value.digitalData[1].activeOrigin = 91;
        var reader = new VivecraftControllerDebug.Actions(FakeAction.class, key -> ((FakeKey) key).getName());
        var left = reader.read(value, 1, 0, "Left trigger", List.of("/user/hand/left /input/trigger slot=click"));
        assertTrue(left.down()); assertTrue(reader.read(value, 1, 0, "", List.of()).down());
        assertFalse(reader.read(value, 0, 1, "", List.of()).down());
        assertEquals("key.attack", left.key()); assertEquals("/actions/ingame/in/key.attack", left.name());
        assertEquals(0, left.hand()); assertEquals(7, value.currentHand);
        assertTrue(value.digitalData[1].state);
    }
    @Test void analogValuesRetainSmallMovementsAndSignedAxesWithoutFabricatingInactiveValues() throws Exception {
        FakeAction value = new FakeAction(); value.type = "vector2";
        value.analogData[0].isActive = true;
        value.analogData[0].x = -.12f; value.analogData[0].y = .25f; value.analogData[0].z = 8;
        var reader = new VivecraftControllerDebug.Actions(FakeAction.class, key -> ((FakeKey) key).getName());
        var sample = reader.read(value, 0, 1, "", List.of());
        assertFalse(sample.down()); assertTrue(sample.actuated());
        assertEquals(-.12f, sample.x()); assertEquals(.25f, sample.y()); assertEquals(0, sample.z());
        value.analogData[0].isActive = false;
        sample = reader.read(value, 0, 1, "", List.of());
        assertFalse(sample.actuated()); assertEquals(0, sample.x()); assertEquals("inactive", sample.value());
        value.analogData[0].isActive = true; value.analogData[0].x = Float.NaN; value.analogData[0].y = Float.POSITIVE_INFINITY;
        assertFalse(reader.read(value, 0, 1, "", List.of()).actuated());
    }
    @Test void physicalHandLabelsFollowReverseHandsAndAll64ButtonBitsAreRepresentable() {
        assertEquals(0, ControllerDebugSnapshot.physicalHand(1, false));
        assertEquals(1, ControllerDebugSnapshot.physicalHand(0, false));
        assertEquals(0, ControllerDebugSnapshot.physicalHand(0, true));
        assertEquals(1, ControllerDebugSnapshot.physicalHand(1, true));
        assertTrue(ControllerDebugSnapshot.bit(Long.MIN_VALUE, 63));
        assertTrue(ControllerDebugSnapshot.bit(1L << 33, 33));
        assertFalse(ControllerDebugSnapshot.bit(1L << 33, 1));
        assertFalse(ControllerDebugSnapshot.bit(-1, 64));
    }
    @Test void reportKeepsFullActionPathBindingsAndUnroundedAxisForDevelopment() {
        var snapshot = new ControllerDebugSnapshot("live", "MCOpenVR", List.of(new ControllerDebugSnapshot.Hand(0, 2,
                "Touch", true, true, 1L << 33, 1L << 32,
                List.of(new ControllerDebugSnapshot.Axis(0, 2, -.1234f, .4567f)), List.of("#33 k_EButton_SteamVR_Trigger [press]"))),
                List.of(new ControllerDebugSnapshot.Action("/actions/gui/in/vivecraft.key.guiLeftClick", "vivecraft.key.guiLeftClick",
                        "boolean", 0, true, true, 0, 0, 0, "Left Trigger", List.of("/user/hand/left /input/trigger mode=button slot=click"))));
        String report = snapshot.report();
        assertTrue(report.contains("pressed=0x0000000200000000"));
        assertTrue(report.contains("x=-0.1234 y=+0.4567"));
        assertTrue(report.contains("/actions/gui/in/vivecraft.key.guiLeftClick"));
        assertTrue(report.contains("/input/trigger mode=button slot=click"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.actions().clear());
    }
    @Test void absentVivecraftIsASafeEmptySnapshot() {
        assertEquals("missing", new VivecraftControllerDebug().read().status());
        assertTrue(new VivecraftControllerDebug().read().hands().isEmpty());
    }
    public static final class FakeKey { public String getName() { return "key.attack"; } }
    public static final class FakeAction {
        public String type = "boolean", name = "/actions/ingame/in/key.attack";
        public FakeKey keyBinding = new FakeKey();
        public long handle = 17;
        public int currentHand = 7;
        public final Digital[] digitalData = {new Digital(), new Digital()};
        public final Analog[] analogData = {new Analog(), new Analog()};
        public boolean isHanded() { return true; }
    }
    public static final class Digital { public boolean isActive, state; public long activeOrigin; }
    public static final class Analog { public boolean isActive; public float x, y, z; public long activeOrigin; }
}
