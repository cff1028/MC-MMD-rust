package com.shiroha.mmdskin.compat.vr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Physical menu controls are independent of Vivecraft's GUI scroll and game turn actions. */
public final class VrMenuActionBindings {
    public static final String OPEN_SET = "/actions/mmdskin_menu_open";
    public static final String CONTROL_SET = "/actions/mmdskin_menu";
    public static final String STICK = OPEN_SET + "/in/right_stick";
    public static final List<String> BUTTONS = List.of("left_trigger_click", "right_trigger_click", "left_grip_click", "right_grip_click");

    private VrMenuActionBindings() {}

    public static void addManifest(JsonObject manifest) {
        JsonArray sets = manifest.getAsJsonArray("action_sets"), actions = manifest.getAsJsonArray("actions");
        Set<String> setNames = new HashSet<>(), actionNames = new HashSet<>();
        for (var e : sets) setNames.add(e.getAsJsonObject().get("name").getAsString());
        for (var e : actions) actionNames.add(e.getAsJsonObject().get("name").getAsString());
        for (String name : List.of(OPEN_SET, CONTROL_SET)) if (setNames.add(name)) {
            JsonObject set = new JsonObject(); set.addProperty("name", name); set.addProperty("usage", "leftright"); sets.add(set);
        }
        addAction(actions, actionNames, STICK, "vector2");
        for (String button : BUTTONS) addAction(actions, actionNames, CONTROL_SET + "/in/" + button, "boolean");
        if (manifest.has("localization")) for (var e : manifest.getAsJsonArray("localization")) {
            JsonObject language = e.getAsJsonObject();
            language.addProperty(OPEN_SET, "MMD Skin - Open spatial menu");
            language.addProperty(CONTROL_SET, "MMD Skin - Spatial menu controls");
            language.addProperty(STICK, "Right stick: forward opens spatial menu");
            for (String button : BUTTONS) language.addProperty(CONTROL_SET + "/in/" + button, button.replace('_', ' '));
        }
    }

    private static void addAction(JsonArray actions, Set<String> names, String name, String type) {
        if (!names.add(name)) return;
        JsonObject action = new JsonObject(); action.addProperty("name", name); action.addProperty("type", type);
        action.addProperty("requirement", "optional"); actions.add(action);
    }

    /** Called on a mod-owned binding copy after diagnostics have catalogued physical sources. */
    public static void addBinding(JsonObject binding) {
        JsonObject sets = binding.getAsJsonObject("bindings");
        if (!sets.has(ControllerDebugBindings.SET)) return;
        JsonArray sources = sets.getAsJsonObject(ControllerDebugBindings.SET).getAsJsonArray("sources");
        JsonArray open = new JsonArray(), controls = new JsonArray();
        JsonObject joystick = null, trackpad = null;
        for (var e : sources) {
            JsonObject source = e.getAsJsonObject(), inputs = source.getAsJsonObject("inputs");
            String path = source.get("path").getAsString();
            if (inputs.has("position")) {
                if (path.equals("/user/hand/right/input/joystick")) joystick = map(source, "position", STICK);
                if (path.equals("/user/hand/right/input/trackpad")) trackpad = map(source, "position", STICK);
            }
            if (!inputs.has("click")) continue;
            String action = inputs.getAsJsonObject("click").get("output").getAsString();
            for (String button : BUTTONS) if (action.equals(ControllerDebugBindings.SET + "/in/" + button))
                controls.add(map(source, "click", CONTROL_SET + "/in/" + button));
        }
        if (joystick != null) open.add(joystick); else if (trackpad != null) open.add(trackpad);
        if (!sets.has(OPEN_SET)) { JsonObject set = new JsonObject(); set.add("sources", open); sets.add(OPEN_SET, set); }
        if (!sets.has(CONTROL_SET)) { JsonObject set = new JsonObject(); set.add("sources", controls); sets.add(CONTROL_SET, set); }
    }

    private static JsonObject map(JsonObject source, String slot, String target) {
        JsonObject copy = source.deepCopy(), input = source.getAsJsonObject("inputs").getAsJsonObject(slot).deepCopy();
        input.addProperty("output", target); JsonObject inputs = new JsonObject(); inputs.add(slot, input); copy.add("inputs", inputs);
        return copy;
    }
}
