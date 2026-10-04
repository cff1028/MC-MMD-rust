package com.shiroha.mmdskin.renderer.runtime.model.loading;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Render-thread request leases for short-lived inventory preview loads. */
public final class PreviewLoadRequests {
    private static final long IDLE_TIMEOUT_NANOS = 2_000_000_000L;
    private final Map<String, Long> lastRequests = new HashMap<>();

    public void requested(String key, long now) { lastRequests.put(key, now); }
    public void resolved(String key) { lastRequests.remove(key); }
    public void removeMatching(Predicate<String> predicate) { lastRequests.keySet().removeIf(predicate); }
    public void clear() { lastRequests.clear(); }

    /** An unfinished native load stays tracked until its result can safely be reclaimed. */
    public void expire(long now, Predicate<String> discardCompleted) {
        lastRequests.entrySet().removeIf(entry -> now - entry.getValue() >= IDLE_TIMEOUT_NANOS
                && discardCompleted.test(entry.getKey()));
    }
}
