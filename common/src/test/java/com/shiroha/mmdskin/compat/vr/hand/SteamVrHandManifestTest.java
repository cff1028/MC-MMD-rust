package com.shiroha.mmdskin.compat.vr.hand;

import com.google.gson.*;
import com.shiroha.mmdskin.compat.vr.ControllerDebugBindings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class SteamVrHandManifestTest {
    @Test void questUiInputsHaveFourIndependentPhysicalSourcesWithoutChangingOriginalBindings() {
        JsonObject original = JsonParser.parseString(BINDING).getAsJsonObject();
        JsonObject output = SteamVrHandManifest.augmentBinding(original);
        String set = com.shiroha.mmdskin.compat.vr.VrUiActionBindings.SET;
        JsonArray sources = output.getAsJsonObject("bindings").getAsJsonObject(set).getAsJsonArray("sources");
        assertEquals(4, sources.size());
        var paths = new java.util.HashSet<String>();
        for (var element : sources) {
            JsonObject source = element.getAsJsonObject();
            String path = source.get("path").getAsString();
            paths.add(path);
            assertEquals(1, source.getAsJsonObject("inputs").size());
            String hand = path.contains("/left/") ? "left" : "right";
            String component = path.substring(path.lastIndexOf('/') + 1);
            assertEquals(set + "/in/" + hand + "_" + component + "_click", source.getAsJsonObject("inputs").getAsJsonObject("click").get("output").getAsString());
        }
        assertEquals(java.util.Set.of("/user/hand/left/input/trigger", "/user/hand/right/input/trigger",
                "/user/hand/left/input/grip", "/user/hand/right/input/grip"), paths);
        assertEquals(original.getAsJsonObject("bindings").get("/actions/gui"), output.getAsJsonObject("bindings").get("/actions/gui"));
        assertEquals(output, SteamVrHandManifest.augmentBinding(output));
    }
    @TempDir Path directory;

    private static final String BINDING = """
            {"controller_type":"oculus_touch","bindings":{"/actions/global":{"sources":[
              {"path":"/user/hand/right/input/trigger","mode":"trigger","inputs":{"click":{"output":"/actions/global/in/attack"}}}
            ]},"/actions/gui":{"sources":[{"path":"/user/hand/left/input/trigger"}]}}}
            """;

    @Test void derivedManifestPreservesOriginalFilesAndOtherControls() throws Exception {
        Path manifest = writeFixture("oculus_touch");
        byte[] originalManifest = Files.readAllBytes(manifest);
        byte[] originalBinding = Files.readAllBytes(directory.resolve("controller.json"));
        Path result = SteamVrHandManifest.prepare(manifest);
        assertEquals(directory.resolve("mmdskin_hand_tracking/action_manifest.json"), result);
        assertArrayEquals(originalManifest, Files.readAllBytes(manifest));
        assertArrayEquals(originalBinding, Files.readAllBytes(directory.resolve("controller.json")));
        JsonObject output = read(result);
        assertEquals(12 + ControllerDebugBindings.CONTROLS.size(), output.getAsJsonArray("actions").size());
        assertEquals("optional", output.getAsJsonArray("actions").get(1).getAsJsonObject().get("requirement").getAsString());
        JsonObject binding = read(result.getParent().resolve(output.getAsJsonArray("default_bindings").get(0).getAsJsonObject().get("binding_url").getAsString()));
        JsonObject original = JsonParser.parseString(BINDING).getAsJsonObject();
        assertEquals(original.getAsJsonObject("bindings").get("/actions/gui"), binding.getAsJsonObject("bindings").get("/actions/gui"));
        assertEquals(original.getAsJsonObject("bindings").getAsJsonObject("/actions/global").get("sources"),
                binding.getAsJsonObject("bindings").getAsJsonObject("/actions/global").get("sources"));
        JsonArray skeleton = binding.getAsJsonObject("bindings").getAsJsonObject("/actions/global").getAsJsonArray("skeleton");
        assertEquals(2, skeleton.size());
        assertEquals("/user/hand/left/input/skeleton/left", skeleton.get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(SteamVrHandManifest.RIGHT_ACTION, skeleton.get(1).getAsJsonObject().get("output").getAsString());
        byte[] prepared = Files.readAllBytes(result);
        assertEquals(result, SteamVrHandManifest.prepare(manifest));
        assertArrayEquals(prepared, Files.readAllBytes(result));
        assertEquals(binding, SteamVrHandManifest.augmentBinding(binding));
    }

    @Test void unrelatedTrackerAndRemoteDefaultsRetainTheirMeaningAfterRelocation() throws Exception {
        Path manifest = writeFixture("vive_tracker_waist");
        JsonObject original = read(manifest);
        JsonObject remote = new JsonObject(); remote.addProperty("controller_type", "unknown");
        remote.addProperty("binding_url", "https://example.invalid/binding.json");
        original.getAsJsonArray("default_bindings").add(remote);
        Files.writeString(manifest, original.toString());
        JsonArray defaults = read(SteamVrHandManifest.prepare(manifest)).getAsJsonArray("default_bindings");
        assertEquals(directory.resolve("controller.json").toString().replace('\\', '/'), defaults.get(0).getAsJsonObject().get("binding_url").getAsString());
        assertEquals(remote, defaults.get(1));
    }

    @Test void steamLinkNativeProfileIsRecognizedWhenPresentWithoutInventingControls() throws Exception {
        Path prepared = SteamVrHandManifest.prepare(writeFixture("svl_hand_interaction_augmented"));
        assertEquals("binding_0.json", read(prepared).getAsJsonArray("default_bindings").get(0).getAsJsonObject().get("binding_url").getAsString());
        assertEquals(1, read(prepared).getAsJsonArray("default_bindings").size());
    }

    @Test void absentGlobalSetFailsBeforeWritingDerivedFiles() throws Exception {
        Path manifest = writeFixture("oculus_touch");
        JsonObject value = read(manifest); value.add("action_sets", new JsonArray());
        Files.writeString(manifest, value.toString());
        assertThrows(IOException.class, () -> SteamVrHandManifest.prepare(manifest));
        assertFalse(Files.exists(directory.resolve("mmdskin_hand_tracking")));
    }

    private Path writeFixture(String controller) throws Exception {
        Path manifest = directory.resolve("action_manifest.json");
        Files.writeString(manifest, """
                {"actions":[{"name":"/actions/global/in/attack","type":"boolean"}],
                 "action_sets":[{"name":"/actions/global","usage":"leftright"}],
                 "localization":[{"language_tag":"en_US"}],
                 "default_bindings":[{"controller_type":"%s","binding_url":"controller.json"}]}
                """.formatted(controller));
        Files.writeString(directory.resolve("controller.json"), BINDING);
        return manifest;
    }

    private JsonObject read(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
}
