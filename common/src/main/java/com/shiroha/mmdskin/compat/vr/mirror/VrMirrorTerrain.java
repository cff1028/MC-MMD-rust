package com.shiroha.mmdskin.compat.vr.mirror;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;

import java.util.Comparator;
import java.util.List;

/** Read the main view's section ring without moving it or running its occlusion graph. */
public final class VrMirrorTerrain {
    private VrMirrorTerrain() {}

    public static void collect(ViewArea area, Camera camera, Frustum frustum,
                               List<RenderSection> visible, List<RenderSection> nearby) {
        visible.clear();
        nearby.clear();
        if (area == null) return;
        var empty = Minecraft.getInstance().level.getChunkSource().getLoadedEmptySections();
        for (RenderSection section : area.sections) {
            // Match vanilla's empty-section exclusion; otherwise a tall sky column
            // wastes the bounded compile budget before visible ground can be built.
            if (!empty.contains(section.getSectionNode()) && frustum.isVisible(section.getBoundingBox())) visible.add(section);
        }
        // Opaque layers draw near to far; vanilla traverses this list backwards for glass.
        var eye = camera.getPosition();
        visible.sort(Comparator.comparingDouble(section ->
                section.getBoundingBox().getCenter().distanceToSqr(eye)));
    }

    public static void submitMissing(List<RenderSection> visible, SectionRenderDispatcher dispatcher,
                                     VrMirrorCompileBudget budget, long gameTick) {
        if (dispatcher == null) return;
        RenderRegionCache regions = null;
        for (RenderSection section : visible) {
            if (!section.isDirty() || !section.hasAllNeighbors()) continue;
            if (!budget.tryAcquire(gameTick, System.nanoTime())) break;
            if (regions == null) regions = new RenderRegionCache();
            // Never rebuild synchronously, drain uploads, or reorder the main view's
            // transparency here. Its normal pass owns those queues and its camera.
            section.rebuildSectionAsync(dispatcher, regions);
            section.setNotDirty();
        }
    }
}
