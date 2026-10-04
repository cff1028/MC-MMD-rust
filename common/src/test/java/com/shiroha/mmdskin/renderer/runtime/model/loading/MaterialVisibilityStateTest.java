package com.shiroha.mmdskin.renderer.runtime.model.loading;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MaterialVisibilityStateTest {
    @Test void unchangedConfigurationDoesNotRepeatNativeCalls() {
        var state = new MaterialVisibilityState();
        var countCalls = new AtomicInteger();
        List<String> updates = new ArrayList<>();
        state.synchronize(Set.of(), () -> { fail("No native call for initial visible state"); return 4; },
                (index, visible) -> fail("Nothing to change"));
        state.synchronize(Set.of(1), () -> { countCalls.incrementAndGet(); return 4; },
                (index, visible) -> updates.add(index + ":" + visible));
        state.synchronize(Set.of(1), () -> { countCalls.incrementAndGet(); return 4; },
                (index, visible) -> updates.add(index + ":" + visible));
        assertEquals(1, countCalls.get());
        assertEquals(List.of("1:false"), updates);
    }

    @Test void reusingPreviewReflectsInPlaceConfigEditsAndRestoresNewlyVisibleMaterials() {
        var state = new MaterialVisibilityState();
        var config = new HashSet<>(Set.of(1));
        List<String> updates = new ArrayList<>();
        state.synchronize(config, () -> 5, (i, v) -> updates.add(i + ":" + v));
        config.remove(1);
        config.add(3);
        state.synchronize(config, () -> 5, (i, v) -> updates.add(i + ":" + v));
        state.synchronize(Set.of(), () -> 5, (i, v) -> updates.add(i + ":" + v));
        assertEquals(List.of("1:false", "1:true", "3:false", "3:true"), updates);
    }

    @Test void invalidSavedIndicesNeverReachNativeMaterialAccess() {
        var state = new MaterialVisibilityState();
        List<Integer> updated = new ArrayList<>();
        var requested = new HashSet<Integer>();
        requested.add(null); requested.add(-1); requested.add(5); requested.add(2);
        state.synchronize(requested, () -> 4, (i, v) -> updated.add(i));
        state.synchronize(null, () -> 4, (i, v) -> updated.add(i));
        assertEquals(List.of(2, 2), updated);
    }
}
