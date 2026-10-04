package com.shiroha.mmdskin.compat.vr;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VrMenuActionBindingsTest {
    @Test void manifestIsIdempotentAndUsesAnIndependentAnalogAction() {
        JsonObject manifest = JsonParser.parseString("{\"action_sets\":[],\"actions\":[],\"localization\":[{\"language_tag\":\"en_US\"}]}").getAsJsonObject();
        VrMenuActionBindings.addManifest(manifest); VrMenuActionBindings.addManifest(manifest);
        assertEquals(2, manifest.getAsJsonArray("action_sets").size());
        assertEquals(5, manifest.getAsJsonArray("actions").size());
        assertEquals("vector2", manifest.getAsJsonArray("actions").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(VrMenuActionBindings.STICK, manifest.getAsJsonArray("actions").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test void questUsesFullPhysicalRightStickWithoutChangingGameOrGuiBindings() {
        JsonObject binding = JsonParser.parseString("{\"controller_type\":\"oculus_touch\",\"bindings\":{\"/actions/gui\":{\"sources\":[{\"path\":\"/user/hand/right/input/joystick\",\"mode\":\"scroll\",\"inputs\":{\"scroll\":{\"output\":\"scroll\"}}}]}}}").getAsJsonObject();
        JsonObject originalGui = binding.getAsJsonObject("bindings").getAsJsonObject("/actions/gui").deepCopy();
        ControllerDebugBindings.addBinding(binding); VrMenuActionBindings.addBinding(binding); VrMenuActionBindings.addBinding(binding);
        JsonObject sets = binding.getAsJsonObject("bindings");
        assertEquals(originalGui, sets.getAsJsonObject("/actions/gui"));
        var open = sets.getAsJsonObject(VrMenuActionBindings.OPEN_SET).getAsJsonArray("sources");
        assertEquals(1, open.size()); JsonObject stick = open.get(0).getAsJsonObject();
        assertEquals("/user/hand/right/input/joystick", stick.get("path").getAsString());
        assertEquals("joystick", stick.get("mode").getAsString());
        assertEquals(VrMenuActionBindings.STICK, stick.getAsJsonObject("inputs").getAsJsonObject("position").get("output").getAsString());
        assertEquals(4, sets.getAsJsonObject(VrMenuActionBindings.CONTROL_SET).getAsJsonArray("sources").size());
    }

    @Test void nonJoystickControllerCanUsePhysicalRightTrackpad() {
        JsonObject binding = JsonParser.parseString("{\"controller_type\":\"vive_controller\",\"bindings\":{\"/actions/game\":{\"sources\":[{\"path\":\"/user/hand/right/input/trackpad\",\"mode\":\"dpad\",\"inputs\":{\"north\":{\"output\":\"jump\"}}}]}}}").getAsJsonObject();
        ControllerDebugBindings.addBinding(binding); VrMenuActionBindings.addBinding(binding);
        JsonObject source = binding.getAsJsonObject("bindings").getAsJsonObject(VrMenuActionBindings.OPEN_SET).getAsJsonArray("sources").get(0).getAsJsonObject();
        assertEquals("/user/hand/right/input/trackpad", source.get("path").getAsString());
        assertEquals("trackpad", source.get("mode").getAsString());
    }
}
