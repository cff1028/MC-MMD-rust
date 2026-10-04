package com.shiroha.mmdskin.compat.vr;

import com.google.gson.*;
import java.util.List;

/** UI controls have their own action set, independent of game/GUI remapping and diagnostics. */
public final class VrUiActionBindings {
    public static final String SET = "/actions/mmdskin_ui";
    public static final List<String> NAMES = List.of("left_trigger_click", "right_trigger_click", "left_grip_click", "right_grip_click");
    private VrUiActionBindings() {}
    public static void addManifest(JsonObject manifest) {
        if (manifest.getAsJsonArray("action_sets").asList().stream().anyMatch(e -> SET.equals(e.getAsJsonObject().get("name").getAsString()))) return;
        JsonObject set = new JsonObject(); set.addProperty("name", SET); set.addProperty("usage", "leftright"); manifest.getAsJsonArray("action_sets").add(set);
        for (String name : NAMES) {
            JsonObject action = new JsonObject(); action.addProperty("name", SET + "/in/" + name);
            action.addProperty("type", "boolean"); action.addProperty("requirement", "optional"); manifest.getAsJsonArray("actions").add(action);
        }
        if (manifest.has("localization")) for (var e : manifest.getAsJsonArray("localization")) {
            JsonObject language = e.getAsJsonObject(); language.addProperty(SET, "MMD Skin - UI interaction");
            for (String name : NAMES) language.addProperty(SET + "/in/" + name, name.replace('_', ' '));
        }
    }
    public static void addBinding(JsonObject binding) {
        JsonObject sets = binding.getAsJsonObject("bindings");
        if (sets.has(SET) || !sets.has(ControllerDebugBindings.SET)) return;
        JsonArray sources = new JsonArray();
        for (var e : sets.getAsJsonObject(ControllerDebugBindings.SET).getAsJsonArray("sources")) {
            JsonObject source = e.getAsJsonObject();
            JsonObject inputs = source.getAsJsonObject("inputs");
            if (!inputs.has("click")) continue;
            String action = inputs.getAsJsonObject("click").get("output").getAsString();
            if (NAMES.stream().noneMatch(name -> action.equals(ControllerDebugBindings.SET + "/in/" + name))) continue;
            JsonObject copy = source.deepCopy(), click = inputs.getAsJsonObject("click").deepCopy(), mapped = new JsonObject();
            click.addProperty("output", action.replace(ControllerDebugBindings.SET, SET)); mapped.add("click", click); copy.add("inputs", mapped); sources.add(copy);
        }
        JsonObject ui = new JsonObject(); ui.add("sources", sources); sets.add(SET, ui);
    }
}
