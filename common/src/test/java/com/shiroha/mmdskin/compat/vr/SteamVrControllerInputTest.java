package com.shiroha.mmdskin.compat.vr;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.LongBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SteamVrControllerInputTest {
    @Test void questBindingsReadBothHandsAndBWithoutAnyGuiOrIngameActions() {
        JsonObject binding = JsonParser.parseString("""
                {"controller_type":"oculus_touch","bindings":{"/actions/gui":{"sources":[]},"/actions/global":{"sources":[]}}}
                """).getAsJsonObject();
        JsonObject original = binding.deepCopy();
        ControllerDebugBindings.addBinding(binding);
        assertEquals(original.getAsJsonObject("bindings").get("/actions/gui"), binding.getAsJsonObject("bindings").get("/actions/gui"));
        JsonArray sources = binding.getAsJsonObject("bindings").getAsJsonObject(ControllerDebugBindings.SET).getAsJsonArray("sources");
        for (int hand = 0; hand < 2; hand++) {
            var trigger = source(sources, hand, "trigger");
            assertEquals("trigger", trigger.get("mode").getAsString());
            assertEquals(new ControllerDebugBindings.Control(hand, "trigger", "pull").name(), target(trigger, "pull"));
            var stick = source(sources, hand, "joystick");
            assertEquals("joystick", stick.get("mode").getAsString());
            assertEquals(new ControllerDebugBindings.Control(hand, "joystick", "position").name(), target(stick, "position"));
            assertEquals("0", stick.getAsJsonObject("parameters").get("deadzone_pct").getAsString());
        }
        assertEquals("/actions/mmdskin_debug/in/right_b_click", target(source(sources, 1, "b"), "click"));
        Set<String> expected = new HashSet<>();
        for (var c : ControllerDebugBindings.CONTROLS) expected.add(c.name());
        Set<String> bound = new HashSet<>();
        for (var e : sources) for (var input : e.getAsJsonObject().getAsJsonObject("inputs").entrySet()) {
            String target = input.getValue().getAsJsonObject().get("output").getAsString();
            assertTrue(expected.contains(target)); assertTrue(bound.add(target), "No duplicate or combined-hand action");
        }
        JsonObject once = binding.deepCopy(); ControllerDebugBindings.addBinding(binding); assertEquals(once, binding);
    }

    @Test void otherProfilesRetainFullStickPositionWhenTheirOnlyBindingIsGuiScroll() {
        JsonObject binding = JsonParser.parseString("""
                {"controller_type":"holographic_controller","bindings":{"/actions/gui":{"sources":[
                 {"path":"/user/hand/left/input/joystick","mode":"scroll","inputs":{"scroll":{"output":"/actions/gui/in/scroll"}}}]}}}
                """).getAsJsonObject();
        ControllerDebugBindings.addBinding(binding);
        var sources = binding.getAsJsonObject("bindings").getAsJsonObject(ControllerDebugBindings.SET).getAsJsonArray("sources");
        assertEquals("/actions/mmdskin_debug/in/left_joystick_position", target(source(sources, 0, "joystick"), "position"));
    }

    @Test void modernApiReadsLeftTriggerRightBAndBothSignedStickAxesWithoutPollingAgain() throws Exception {
        FakeInput.reset();
        var api = new SteamVrControllerInput.NativeApi(FakeInput.class, Digital.class, Analog.class, null);
        FakeInput.set(0, "trigger", "pull", .63f, 0, false);
        FakeInput.set(0, "trigger", "click", 0, 0, true);
        FakeInput.set(1, "b", "click", 0, 0, true);
        for (float[] xy : List.of(new float[]{-.9f,0}, new float[]{.8f,0}, new float[]{0,.7f}, new float[]{0,-.6f}, new float[]{-.4f,.3f})) {
            FakeInput.set(0, "joystick", "position", xy[0], xy[1], false);
            FakeInput.set(1, "joystick", "position", -xy[0], -xy[1], false);
            var data = SteamVrControllerInput.read(api, true, h -> List.of());
            assertEquals(.63f, action(data, 0, "trigger", "pull").x());
            assertTrue(action(data, 0, "trigger", "click").down());
            assertTrue(action(data, 1, "b", "click").down());
            assertFalse(action(data, 0, "b", "click").down());
            for (int side = 0; side < 2; side++) {
                var stick = action(data, side, "joystick", "position");
                assertEquals(side == 0 ? xy[0] : -xy[0], stick.x());
                assertEquals(side == 0 ? xy[1] : -xy[1], stick.y());
                assertTrue(stick.actuated());
            }
        }
        assertEquals(0, FakeInput.wrongHandReads);
        // The fake deliberately has no UpdateActionState or GetControllerState API: neither may be used to read.
    }

    @Test void FocusLossAndReadErrorsClearPreviouslyHeldInputs() throws Exception {
        FakeInput.reset(); var api = new SteamVrControllerInput.NativeApi(FakeInput.class, Digital.class, Analog.class, null);
        FakeInput.set(1, "b", "click", 0, 0, true);
        assertTrue(action(SteamVrControllerInput.read(api, true, h -> List.of()), 1, "b", "click").down());
        int before = FakeInput.calls;
        assertTrue(SteamVrControllerInput.read(api, false, h -> List.of()).stream().noneMatch(ControllerDebugSnapshot.Action::actuated));
        assertEquals(before, FakeInput.calls);
        FakeInput.error = true;
        assertTrue(SteamVrControllerInput.read(api, true, h -> List.of()).stream().noneMatch(ControllerDebugSnapshot.Action::active));
        FakeInput.error = false; FakeInput.active = false;
        assertTrue(SteamVrControllerInput.read(api, true, h -> List.of()).stream().noneMatch(ControllerDebugSnapshot.Action::actuated));
    }

    private static JsonObject source(JsonArray sources, int hand, String component) {
        String path = new ControllerDebugBindings.Control(hand, component, "click").path();
        return sources.asList().stream().map(JsonElement::getAsJsonObject).filter(e -> path.equals(e.get("path").getAsString())).findFirst().orElseThrow();
    }
    private static String target(JsonObject source, String slot) { return source.getAsJsonObject("inputs").getAsJsonObject(slot).get("output").getAsString(); }
    private static ControllerDebugSnapshot.Action action(List<ControllerDebugSnapshot.Action> values, int hand, String component, String slot) {
        String name = new ControllerDebugBindings.Control(hand, component, slot).name();
        return values.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }
    public static class Digital {
        boolean active, down;
        public static Digital create() { return new Digital(); }
        public boolean bActive() { return active; } public boolean bState() { return down; }
    }
    public static class Analog {
        boolean active; float x,y;
        public static Analog create() { return new Analog(); }
        public boolean bActive() { return active; } public float x() { return x; } public float y() { return y; }
    }
    public static class FakeInput {
        static Map<String,Long> names = new HashMap<>(); static Map<Long,float[]> values = new HashMap<>();
        static boolean active, error; static int wrongHandReads, calls;
        static void reset() { names.clear(); values.clear(); active=true; error=false; wrongHandReads=calls=0; }
        static void set(int side, String component, String slot, float x, float y, boolean down) { values.put(names.get(new ControllerDebugBindings.Control(side, component, slot).name()),new float[]{x,y,down?1:0}); }
        public static int VRInput_GetActionSetHandle(CharSequence name, LongBuffer result) { result.put(0,999); return 0; }
        public static int VRInput_GetActionHandle(CharSequence name, LongBuffer result) { long handle=names.size()+1; names.put(name.toString(),handle); result.put(0,handle); return 0; }
        public static int VRInput_GetInputSourceHandle(CharSequence name, LongBuffer result) { result.put(0,name.toString().endsWith("left")?100:200); return 0; }
        static float[] read(long handle,long hand) {
            calls++;
            String name=names.entrySet().stream().filter(e->e.getValue()==handle).findFirst().orElseThrow().getKey();
            if ((name.contains("/left_")?100:200)!=hand) wrongHandReads++;
            return values.getOrDefault(handle,new float[3]);
        }
        public static int VRInput_GetDigitalActionData(long h,Digital data,long hand) { float[] v=read(h,hand); if(error)return 3; data.active=active; data.down=v[2]!=0; return 0; }
        public static int VRInput_GetAnalogActionData(long h,Analog data,long hand) { float[] v=read(h,hand); if(error)return 3; data.active=active; data.x=v[0]; data.y=v[1]; return 0; }
    }
}
