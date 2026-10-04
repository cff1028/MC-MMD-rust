package com.shiroha.mmdskin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Supplier;

/** Publishes normalized read-only snapshots; previews never touch the saved global preferences. */
public final class VrPointerConfigManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile VrPointerConfigData current;
    private static Supplier<File> configRootDirSupplier = PathConstants::getConfigRootDir;

    private VrPointerConfigManager() {}

    /** Shared snapshot for rendering. Callers must not modify it; editors use getConfig(). */
    public static VrPointerConfigData getLiveConfig() {
        VrPointerConfigData snapshot = current;
        return snapshot != null ? snapshot : loadOnce();
    }

    private static synchronized VrPointerConfigData loadOnce() {
        if (current != null) return current;
        File file = getConfigFile();
        if (file.isFile()) {
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                JsonElement json = JsonParser.parseReader(reader);
                if (json.isJsonObject()) {
                    migrateLegacyRadius(json.getAsJsonObject(), "dotRadius", "dotRadiusView");
                    migrateLegacyRadius(json.getAsJsonObject(), "pressedDotRadius", "pressedDotRadiusView");
                    migrateLegacyCrosshair(json.getAsJsonObject());
                }
                VrPointerConfigData data = GSON.fromJson(json, VrPointerConfigData.class);
                current = data == null ? new VrPointerConfigData() : data.normalizeInPlace();
            } catch (Exception exception) {
                LOGGER.warn("Could not read VR pointer preferences; using defaults", exception);
            }
        }
        if (current == null) current = new VrPointerConfigData();
        return current;
    }

    /** Preserve the old size at one metre with a 90-degree view. Loading never rewrites the user's JSON. */
    private static void migrateLegacyRadius(JsonObject json, String legacyKey, String viewKey) {
        if (json.has(viewKey) || !json.has(legacyKey) || json.get(legacyKey).isJsonNull()) return;
        try {
            float legacy = json.get(legacyKey).getAsFloat();
            if (Float.isFinite(legacy)) json.addProperty(viewKey, legacy * .5f);
        } catch (RuntimeException ignored) {
            // An unusable legacy radius must not discard unrelated valid preferences.
        }
    }

    /** An explicit new mode always wins, including invalid values that normalize to OFF. */
    private static void migrateLegacyCrosshair(JsonObject json) {
        if (json.has("hideCrosshairMode")) return;
        JsonElement legacy = json.get("hideOriginalCrosshair");
        if (legacy != null && legacy.isJsonPrimitive() && legacy.getAsJsonPrimitive().isBoolean()) {
            json.addProperty("hideCrosshairMode", legacy.getAsBoolean() ? "ALWAYS" : "OFF");
        }
    }

    public static VrPointerConfigData getConfig() { return getLiveConfig().copy(); }
    public static synchronized void preview(VrPointerConfigData data) {
        current = data == null ? new VrPointerConfigData() : data.normalizedCopy();
    }
    public static void previewConfig(VrPointerConfigData data) { preview(data); }

    /** Writes a complete temporary JSON before replacing the previous file. Failure keeps the editor open. */
    public static synchronized boolean save(VrPointerConfigData data) {
        VrPointerConfigData normalized = data == null ? new VrPointerConfigData() : data.normalizedCopy();
        Path destination = getConfigFile().toPath();
        Path temporary = null;
        try {
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(destination.getParent(), "vr-pointer-", ".tmp");
            Files.writeString(temporary, GSON.toJson(normalized), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            current = normalized;
            return true;
        } catch (Exception exception) {
            LOGGER.error("Could not save VR pointer preferences", exception);
            return false;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
        }
    }
    public static boolean saveConfig(VrPointerConfigData data) { return save(data); }
    public static synchronized void invalidate() { current = null; }
    public static synchronized File getConfigFile() {
        return new File(Objects.requireNonNullElseGet(configRootDirSupplier.get(), PathConstants::getConfigRootDir), "vr_pointer.json");
    }
    static synchronized void setConfigRootDirSupplierForTesting(Supplier<File> supplier) {
        configRootDirSupplier = supplier == null ? PathConstants::getConfigRootDir : supplier;
        current = null;
    }
}
