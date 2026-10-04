package com.shiroha.mmdskin.ui.spatial.backend;

import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.function.Function;

/** Keeps the last completed native list while a fresh scan is pending; failed scans are never an empty-list success. */
final class AsyncWorldCatalog {
    private final Function<String, CompletableFuture<List<LevelSummary>>> loader;
    private CompletableFuture<List<LevelSummary>> pending;
    private List<LevelSummary> entries = List.of();
    private String error = "";
    private long revision;
    AsyncWorldCatalog(LevelStorageSource source) {
        loader = excludedLevel -> CompletableFuture.supplyAsync(() -> {
            try { return withoutLevel(source.findLevelCandidates(), excludedLevel); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }).thenCompose(source::loadLevelSummaries);
    }
    AsyncWorldCatalog(Supplier<CompletableFuture<List<LevelSummary>>> loader) { this.loader = ignored -> loader.get(); }
    static LevelStorageSource.LevelCandidates withoutLevel(LevelStorageSource.LevelCandidates candidates, String excludedLevel) {
        if (excludedLevel == null || excludedLevel.isBlank()) return candidates;
        return new LevelStorageSource.LevelCandidates(candidates.levels().stream()
                .filter(directory -> !directory.directoryName().equals(excludedLevel)).toList());
    }
    void refresh() { refresh(""); }
    void refresh(String excludedLevel) {
        error = "";
        try { pending = loader.apply(excludedLevel); }
        catch (RuntimeException failure) { pending = CompletableFuture.failedFuture(failure); }
    }
    void poll() {
        if (pending == null || !pending.isDone()) return;
        var completed = pending; pending = null;
        try {
            entries = completed.join().stream().sorted().toList();
            revision++; error = "";
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            String detail = cause.getMessage();
            error = "无法读取当前实例的存档" + (detail == null || detail.isBlank() ? "。请检查存档目录后刷新。" : "：" + detail);
        }
    }
    List<LevelSummary> entries() { poll(); return entries; }
    boolean loading() { poll(); return pending != null; }
    String error() { poll(); return error; }
    long revision() { poll(); return revision; }
    void close() { if (pending != null) pending.cancel(false); pending = null; entries = List.of(); }
}
