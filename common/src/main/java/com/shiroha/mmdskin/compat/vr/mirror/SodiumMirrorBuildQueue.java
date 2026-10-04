package com.shiroha.mmdskin.compat.vr.mirror;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Missing mirror geometry joins the normal builder without holding unloaded sections alive. */
final class SodiumMirrorBuildQueue<T> {
    private static final int MAX_PENDING = 4096;
    private final ArrayDeque<WeakReference<T>> pending = new ArrayDeque<>();
    private final WeakHashMap<T, Boolean> known = new WeakHashMap<>();
    private final VrMirrorCompileBudget budget = new VrMirrorCompileBudget();

    static boolean isGeometryUpdate(String type) {
        return "INITIAL_BUILD".equals(type) || "REBUILD".equals(type) || "IMPORTANT_REBUILD".equals(type);
    }

    void offer(T section) {
        if (known.containsKey(section)) return;
        while (!pending.isEmpty() && pending.peek().get() == null) pending.remove();
        if (pending.size() >= MAX_PENDING) return;
        known.put(section, Boolean.TRUE);
        pending.add(new WeakReference<>(section));
    }

    int drain(long tick, LongSupplier clock, Predicate<T> eligible, Consumer<T> submit) {
        int submitted = 0;
        // Dispose stale requests cheaply, but never scan an unbounded queue in an eye pass.
        for (int checked = 0; checked < 256 && !pending.isEmpty(); checked++) {
            T section = pending.peek().get();
            if (section == null || !eligible.test(section)) {
                pending.remove();
                if (section != null) known.remove(section);
                continue;
            }
            if (!budget.tryAcquire(tick, clock.getAsLong())) break;
            pending.remove();
            known.remove(section);
            submit.accept(section);
            submitted++;
        }
        return submitted;
    }

    int pendingCount() { return pending.size(); }
}
