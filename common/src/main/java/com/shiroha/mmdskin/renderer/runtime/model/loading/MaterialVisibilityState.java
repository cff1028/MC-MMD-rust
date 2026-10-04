package com.shiroha.mmdskin.renderer.runtime.model.loading;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/** Tracks configured material changes without querying native material state every frame. */
public final class MaterialVisibilityState {
    private Set<Integer> appliedHidden = Set.of();

    public void synchronize(Set<Integer> requestedHidden, IntSupplier materialCount,
                            BiConsumer<Integer, Boolean> setVisible) {
        Set<Integer> requested = requestedHidden == null ? Set.of() : requestedHidden;
        if (appliedHidden.equals(requested)) return;

        int count = materialCount.getAsInt();
        for (Integer index : appliedHidden) {
            if (isValid(index, count) && !requested.contains(index)) setVisible.accept(index, true);
        }
        for (Integer index : requested) {
            if (isValid(index, count) && !appliedHidden.contains(index)) setVisible.accept(index, false);
        }
        // Do not retain the mutable live config set: editing it must trigger another update.
        appliedHidden = new HashSet<>(requested);
    }

    private static boolean isValid(Integer index, int count) {
        return index != null && index >= 0 && index < count;
    }
}
