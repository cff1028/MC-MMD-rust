package com.shiroha.mmdskin.ui.spatial.backend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shiroha.mmdskin.config.PathConstants;
import com.shiroha.mmdskin.ui.spatial.SpatialMenuTransforms;
import java.nio.file.Files;
import java.nio.file.Path;

/** Only preferences owned by the new menu. Loading is read-only. */
public final class SpatialMenuPreferences {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public boolean reducedMotion;
    public boolean highContrast;
    public double panelDistance = 1.35;
    public double panelScale = 1.0;
    public SpatialMenuTransforms.DetailBehavior detailBehavior = SpatialMenuTransforms.DetailBehavior.FIXED_CLOSE;
    public double closeDistance = 4.0;
    public double positionSmoothingMs = 180;
    public double rotationSmoothingMs = 180;
    public java.util.Set<String> favorites = new java.util.HashSet<>();
    public static SpatialMenuPreferences load() {
        try {
            if (Files.isRegularFile(path())) {
                SpatialMenuPreferences value = JSON.fromJson(Files.readString(path()), SpatialMenuPreferences.class);
                if (value != null) {
                    value.normalize();
                    return value;
                }
            }
        } catch (Exception ignored) {}
        return new SpatialMenuPreferences();
    }
    public void normalize() {
        if (favorites == null) favorites = new java.util.HashSet<>();
        favorites.removeIf(java.util.Objects::isNull);
        panelDistance = Double.isFinite(panelDistance) ? Math.clamp(panelDistance, .6, 2.5) : 1.35;
        panelScale = Double.isFinite(panelScale) ? Math.clamp(panelScale, .75, 1.25) : 1;
        if (detailBehavior == null) detailBehavior = SpatialMenuTransforms.DetailBehavior.FIXED_CLOSE;
        closeDistance = SpatialMenuTransforms.minimumCloseDistance(panelDistance, closeDistance);
        positionSmoothingMs = Double.isFinite(positionSmoothingMs) ? Math.clamp(positionSmoothingMs, 0, 2000) : 180;
        rotationSmoothingMs = Double.isFinite(rotationSmoothingMs) ? Math.clamp(rotationSmoothingMs, 0, 2000) : 180;
    }
    /** Only explicit distance edits resize the closing radius; loading must never compound it. */
    public void setPanelDistance(double value) {
        normalize();
        double closingRatio = closeDistance / panelDistance;
        panelDistance = Double.isFinite(value) ? Math.clamp(value, .6, 2.5) : 1.35;
        closeDistance = SpatialMenuTransforms.minimumCloseDistance(panelDistance, panelDistance * closingRatio);
    }
    public void save() throws java.io.IOException {
        normalize();
        Files.createDirectories(path().getParent());
        Path temp = Files.createTempFile(path().getParent(), "spatial-menu-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(this));
            Files.move(temp, path(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static Path path() { return PathConstants.getConfigRootDir().toPath().resolve("vr_menu.json"); }
}
