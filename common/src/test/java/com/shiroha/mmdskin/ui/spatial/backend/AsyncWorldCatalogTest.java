package com.shiroha.mmdskin.ui.spatial.backend;

import net.minecraft.world.level.storage.LevelSummary;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class AsyncWorldCatalogTest {
    private static LevelSummary summary(String name) { return new LevelSummary(null, null, name, false, false, false, Path.of(name, "icon.png")); }
    @Test void excludesOnlyTheLiveIntegratedServerDirectoryWithoutReadingOrUnlockingIt() {
        var current = new net.minecraft.world.level.storage.LevelStorageSource.LevelDirectory(Path.of("saves", "playing"));
        var other = new net.minecraft.world.level.storage.LevelStorageSource.LevelDirectory(Path.of("saves", "other"));
        var candidates = new net.minecraft.world.level.storage.LevelStorageSource.LevelCandidates(List.of(current, other));
        assertEquals(List.of(other), AsyncWorldCatalog.withoutLevel(candidates, "playing").levels());
        assertSame(candidates, AsyncWorldCatalog.withoutLevel(candidates, ""));
        assertEquals(List.of(current, other), candidates.levels());
    }
    @Test void completedNativeListSurvivesPendingRefreshAndFailureThenTracksDeletion() {
        var first = new CompletableFuture<List<LevelSummary>>();
        var failed = new CompletableFuture<List<LevelSummary>>();
        var deleted = new CompletableFuture<List<LevelSummary>>();
        var jobs = new ArrayDeque<>(List.of(first, failed, deleted));
        var catalog = new AsyncWorldCatalog(jobs::removeFirst);
        catalog.refresh(); assertTrue(catalog.loading()); assertTrue(catalog.entries().isEmpty());
        var world = summary("existing-world"); first.complete(List.of(world));
        assertEquals(List.of(world), catalog.entries()); assertFalse(catalog.loading());
        catalog.refresh(); assertEquals(List.of(world), catalog.entries()); assertTrue(catalog.loading());
        failed.completeExceptionally(new java.io.IOException("Permission denied"));
        assertEquals(List.of(world), catalog.entries()); assertTrue(catalog.error().contains("Permission denied"));
        catalog.refresh(); deleted.complete(List.of());
        assertTrue(catalog.entries().isEmpty()); assertEquals("", catalog.error()); assertFalse(catalog.loading());
    }
    @Test void olderScanCompletionCannotReplaceNewerRefreshResult() {
        var old = new CompletableFuture<List<LevelSummary>>(); var latest = new CompletableFuture<List<LevelSummary>>();
        var jobs = new ArrayDeque<>(List.of(old, latest)); var catalog = new AsyncWorldCatalog(jobs::removeFirst);
        catalog.refresh(); catalog.refresh();
        var newWorld = summary("new-world"); latest.complete(List.of(newWorld));
        assertEquals(List.of(newWorld), catalog.entries());
        old.complete(List.of(summary("stale-world")));
        assertEquals(List.of(newWorld), catalog.entries());
    }
}
