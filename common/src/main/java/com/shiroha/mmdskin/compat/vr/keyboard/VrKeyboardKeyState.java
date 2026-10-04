package com.shiroha.mmdskin.compat.vr.keyboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** A physical key stays down while any hand/finger owns it, with a single repeat clock. */
final class VrKeyboardKeyState {
    private static final long REPEAT_DELAY = 450_000_000L;
    private static final long REPEAT_INTERVAL = 70_000_000L;
    private final Map<String, Long> repeats = new HashMap<>();

    List<String> update(Collection<String> active, Collection<String> newlyPressed,
                        Predicate<String> repeatable, long now) {
        var keys = new LinkedHashSet<>(active);
        repeats.keySet().retainAll(keys);
        List<String> emitted = new ArrayList<>();
        for (String key : keys) {
            Long repeatAt = repeats.get(key);
            if (repeatAt == null) {
                repeats.put(key, now + REPEAT_DELAY);
                if (newlyPressed.contains(key)) emitted.add(key);
            } else if (repeatable.test(key) && now >= repeatAt) {
                repeats.put(key, now + REPEAT_INTERVAL);
                emitted.add(key);
            }
        }
        return emitted;
    }

    void reset() { repeats.clear(); }
}
