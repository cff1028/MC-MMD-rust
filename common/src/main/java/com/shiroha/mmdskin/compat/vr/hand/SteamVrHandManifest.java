package com.shiroha.mmdskin.compat.vr.hand;

import com.google.gson.*;
import com.shiroha.mmdskin.compat.vr.ControllerDebugBindings;
import com.shiroha.mmdskin.compat.vr.SteamVrControllerInput;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;

/** Adds optional skeletal actions through mod-owned copies; existing bindings are never edited. */
public final class SteamVrHandManifest {
    public static final String LEFT_ACTION = "/actions/global/in/mmdskin_skeleton_left";
    public static final String RIGHT_ACTION = "/actions/global/in/mmdskin_skeleton_right";
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> HAND_CONTROLLERS = Set.of(
            "knuckles", "oculus_touch", "vive_controller", "holographic_controller", "vive_cosmos_controller",
            "svl_hand_interaction_augmented");
    private static boolean failureLogged;

    private SteamVrHandManifest() {}

    /** Called only at Vivecraft's initial SetActionManifestPath call, after its defaults exist. */
    public static CharSequence prepareManifest(CharSequence originalPath) {
        try {
            Path prepared = prepare(Path.of(originalPath.toString()));
            SteamVrHandProvider.reset();
            SteamVrControllerInput.reset();
            com.shiroha.mmdskin.compat.vr.SteamVrUiInput.reset();
            com.shiroha.mmdskin.compat.vr.SteamVrMenuInput.reset();
            com.shiroha.mmdskin.ui.spatial.SpatialMenuVrBridge.reset();
            LOGGER.info("[VR hands] Optional skeletal actions use {}. Existing Vivecraft and SteamVR user bindings are preserved.", prepared);
            return prepared.toString();
        } catch (IOException | RuntimeException failure) {
            if (!failureLogged) {
                LOGGER.warn("[VR hands] Could not prepare optional skeletal actions; controller tracking remains available", failure);
                failureLogged = true;
            }
            return originalPath;
        }
    }

    static Path prepare(Path original) throws IOException {
        original = original.toAbsolutePath().normalize();
        Path input = original.getParent();
        JsonObject manifest = read(original);
        boolean hasGlobal = manifest.getAsJsonArray("action_sets").asList().stream()
                .anyMatch(value -> "/actions/global".equals(value.getAsJsonObject().get("name").getAsString()));
        if (!hasGlobal) throw new IOException("Vivecraft global action set is absent");
        addAction(manifest, LEFT_ACTION, "left");
        addAction(manifest, RIGHT_ACTION, "right");
        ControllerDebugBindings.addManifest(manifest);
        com.shiroha.mmdskin.compat.vr.VrUiActionBindings.addManifest(manifest);
        com.shiroha.mmdskin.compat.vr.VrMenuActionBindings.addManifest(manifest);
        if (manifest.has("localization")) for (JsonElement value : manifest.getAsJsonArray("localization")) {
            JsonObject language = value.getAsJsonObject();
            language.addProperty(LEFT_ACTION, "MMD Skin - Left hand skeleton");
            language.addProperty(RIGHT_ACTION, "MMD Skin - Right hand skeleton");
        }
        Path output = input.resolve("mmdskin_hand_tracking");
        Files.createDirectories(output);
        int ordinal = 0;
        for (JsonElement value : manifest.getAsJsonArray("default_bindings")) {
            JsonObject binding = value.getAsJsonObject();
            String url = binding.get("binding_url").getAsString();
            // Remote/custom URLs remain untouched. Local URLs need an absolute base after relocation.
            if (url.contains("://")) continue;
            Path source = input.resolve(url).normalize().toAbsolutePath();
            binding.addProperty("binding_url", source.toString().replace('\\', '/'));
            if (!HAND_CONTROLLERS.contains(binding.get("controller_type").getAsString())
                    || !source.startsWith(input) || !Files.isRegularFile(source)) continue;
            JsonObject augmented = augmentBinding(read(source));
            String filename = "binding_" + ordinal++ + ".json";
            writeOwned(output.resolve(filename), GSON.toJson(augmented));
            binding.addProperty("binding_url", filename);
        }
        Path result = output.resolve("action_manifest.json");
        writeOwned(result, GSON.toJson(manifest));
        return result;
    }

    static JsonObject augmentBinding(JsonObject original) {
        JsonObject result = original.deepCopy();
        JsonObject bindings = object(result, "bindings");
        JsonObject global = object(bindings, "/actions/global");
        JsonArray skeleton = global.has("skeleton") ? global.getAsJsonArray("skeleton") : new JsonArray();
        global.add("skeleton", skeleton);
        addSkeletonBinding(skeleton, LEFT_ACTION, "left");
        addSkeletonBinding(skeleton, RIGHT_ACTION, "right");
        ControllerDebugBindings.addBinding(result);
        com.shiroha.mmdskin.compat.vr.VrUiActionBindings.addBinding(result);
        com.shiroha.mmdskin.compat.vr.VrMenuActionBindings.addBinding(result);
        return result;
    }

    private static void addSkeletonBinding(JsonArray skeleton, String action, String side) {
        for (JsonElement value : skeleton) if (value.isJsonObject() && value.getAsJsonObject().has("output")
                && action.equals(value.getAsJsonObject().get("output").getAsString())) return;
        JsonObject binding = new JsonObject();
        binding.addProperty("output", action);
        binding.addProperty("path", "/user/hand/" + side + "/input/skeleton/" + side);
        skeleton.add(binding);
    }

    private static void addAction(JsonObject manifest, String action, String side) {
        JsonArray actions = manifest.getAsJsonArray("actions");
        for (JsonElement value : actions) if (action.equals(value.getAsJsonObject().get("name").getAsString())) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("name", action);
        entry.addProperty("type", "skeleton");
        entry.addProperty("skeleton", "/skeleton/hand/" + side);
        entry.addProperty("requirement", "optional");
        actions.add(entry);
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (!parent.has(name)) parent.add(name, new JsonObject());
        return parent.getAsJsonObject(name);
    }

    private static JsonObject read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void writeOwned(Path path, String json) throws IOException {
        if (Files.isRegularFile(path) && Files.readString(path, StandardCharsets.UTF_8).equals(json)) return;
        Path staging = Files.createTempFile(path.getParent(), ".mmdskin-", ".json");
        try {
            Files.writeString(staging, json, StandardCharsets.UTF_8);
            try { Files.move(staging, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(staging);
        }
    }
}
