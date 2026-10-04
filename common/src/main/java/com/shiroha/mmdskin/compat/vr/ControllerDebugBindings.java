package com.shiroha.mmdskin.compat.vr;

import com.google.gson.*;
import java.util.*;

/** Separate physical input actions: never reuse GUI scroll or gameplay bindings as device state. */
public final class ControllerDebugBindings {
    public static final String SET = "/actions/mmdskin_debug";
    public record Control(int hand, String component, String slot) {
        public String side() { return hand == 0 ? "left" : "right"; }
        public String path() { return "/user/hand/" + side() + "/input/" + component; }
        public String name() { return SET + "/in/" + side() + "_" + component + "_" + slot; }
        public String label() { return component + "/" + slot; }
        public String type() { return slot.equals("position") ? "vector2" : slot.equals("pull") ? "vector1" : "boolean"; }
    }
    public static final List<Control> CONTROLS;
    static {
        List<Control> controls = new ArrayList<>();
        for (int hand = 0; hand < 2; hand++) {
            for (String component : List.of("joystick", "trigger", "grip", "trackpad", "a", "b", "x", "y", "application_menu", "thumbrest", "system")) {
                if (component.equals("joystick") || component.equals("trackpad")) controls.add(new Control(hand, component, "position"));
                if (component.equals("trigger") || component.equals("grip")) controls.add(new Control(hand, component, "pull"));
                for (String slot : List.of("click", "touch")) controls.add(new Control(hand, component, slot));
            }
        }
        CONTROLS = List.copyOf(controls);
    }
    private ControllerDebugBindings() {}
    public static boolean isDebug(ControllerDebugSnapshot.Action action) { return action.name().startsWith(SET + "/in/"); }

    public static void addManifest(JsonObject manifest) {
        JsonArray sets = manifest.getAsJsonArray("action_sets"), actions = manifest.getAsJsonArray("actions");
        if (sets.asList().stream().noneMatch(e -> SET.equals(e.getAsJsonObject().get("name").getAsString()))) {
            JsonObject set = new JsonObject(); set.addProperty("name", SET); set.addProperty("usage", "leftright"); sets.add(set);
        }
        Set<String> names = new HashSet<>();
        for (JsonElement e : actions) names.add(e.getAsJsonObject().get("name").getAsString());
        for (Control control : CONTROLS) if (names.add(control.name())) {
            JsonObject action = new JsonObject(); action.addProperty("name", control.name());
            action.addProperty("type", control.type()); action.addProperty("requirement", "optional"); actions.add(action);
        }
        if (manifest.has("localization")) for (JsonElement e : manifest.getAsJsonArray("localization")) {
            JsonObject lang = e.getAsJsonObject(); lang.addProperty(SET, "MMD Skin - Controller diagnostics");
            for (Control control : CONTROLS) lang.addProperty(control.name(), control.side() + " " + control.label());
        }
    }

    /** Augment only the mod-owned copy. The existing sets and their sources remain byte-for-byte equivalent JSON. */
    public static void addBinding(JsonObject binding) {
        JsonObject sets = binding.getAsJsonObject("bindings");
        if (sets.has(SET)) return;
        String profile = binding.has("controller_type") ? binding.get("controller_type").getAsString() : "";
        JsonArray sources = new JsonArray();
        if (profile.equals("oculus_touch")) {
            // Quest/Steam Link Touch profile. These paths also exist when not bound in Vivecraft's GUI set.
            for (int hand = 0; hand < 2; hand++) {
                addSource(sources, hand, "joystick", "joystick", "position", "click", "touch");
                addSource(sources, hand, "trigger", "trigger", "pull", "click", "touch");
                addSource(sources, hand, "grip", "trigger", "pull", "click", "touch");
                for (String button : hand == 0 ? List.of("x", "y") : List.of("a", "b"))
                    addSource(sources, hand, button, "button", "click", "touch");
                addSource(sources, hand, "thumbrest", "button", "touch");
            }
            // System buttons may be reserved by SteamVR; never synthesize them from another button.
            addSource(sources, 0, "system", "button", "click", "touch");
        } else {
            // For other profiles use their existing physical sources, including currently inactive game sets.
            // A scroll/dpad binding still comes from a full two-dimensional stick/trackpad.
            Set<String> seen = new HashSet<>();
            for (var set : sets.entrySet()) if (set.getValue().isJsonObject()) {
                JsonObject value = set.getValue().getAsJsonObject();
                if (!value.has("sources")) continue;
                for (JsonElement e : value.getAsJsonArray("sources")) {
                    JsonObject source = e.getAsJsonObject();
                    if (!source.has("path") || !source.has("mode")) continue;
                    String path = source.get("path").getAsString(), mode = source.get("mode").getAsString();
                    int hand = path.startsWith("/user/hand/left/input/") ? 0 : path.startsWith("/user/hand/right/input/") ? 1 : -1;
                    if (hand < 0) continue;
                    String component = path.substring(path.lastIndexOf('/') + 1);
                    if (CONTROLS.stream().noneMatch(c -> c.component().equals(component))) continue;
                    if (component.equals("joystick") || component.equals("trackpad")) {
                        if (seen.add(path + "/position")) addSource(sources, hand, component, component, "position");
                    }
                    if (source.has("inputs")) for (String slot : source.getAsJsonObject("inputs").keySet()) {
                        if (CONTROLS.stream().noneMatch(c -> c.component().equals(component) && c.slot().equals(slot)) || !seen.add(path + "/" + slot)) continue;
                        String inputMode = slot.equals("position") ? component : slot.equals("pull") ? "trigger" : "button";
                        addSource(sources, hand, component, inputMode, slot);
                    }
                }
            }
        }
        JsonObject debug = new JsonObject(); debug.add("sources", sources); sets.add(SET, debug);
    }

    private static void addSource(JsonArray sources, int hand, String component, String mode, String... slots) {
        JsonObject source = new JsonObject(), inputs = new JsonObject(), params = new JsonObject();
        source.addProperty("path", new Control(hand, component, "click").path()); source.addProperty("mode", mode);
        for (String slot : slots) { JsonObject out = new JsonObject(); out.addProperty("output", new Control(hand, component, slot).name()); inputs.add(slot, out); }
        source.add("inputs", inputs);
        params.addProperty("haptic_amplitude", "0");
        if (mode.equals("joystick")) params.addProperty("deadzone_pct", "0");
        source.add("parameters", params); sources.add(source);
    }
}
