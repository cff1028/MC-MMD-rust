package com.shiroha.mmdskin.renderer.runtime.model.loading;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelLoadCoordinatorTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Future<ModelLoadCoordinator.AsyncLoadResult>> pending(ModelLoadCoordinator coordinator)
            throws ReflectiveOperationException {
        var field = ModelLoadCoordinator.class.getDeclaredField("pendingLoads");
        field.setAccessible(true);
        return (Map<String, Future<ModelLoadCoordinator.AsyncLoadResult>>) field.get(coordinator);
    }

    @Test void completedUnusedPreviewIsReclaimedExactlyOnceWithoutTouchingWorldLoad() throws Exception {
        var coordinator = new ModelLoadCoordinator();
        var result = new ModelLoadCoordinator.AsyncLoadResult(7L, null, "preview");
        pending(coordinator).put("preview", CompletableFuture.completedFuture(result));
        pending(coordinator).put("world", new CompletableFuture<>());
        var freed = new AtomicInteger();
        assertTrue(coordinator.discardCompleted("preview", r -> { assertSame(result, r); freed.incrementAndGet(); }));
        assertTrue(coordinator.discardCompleted("preview", r -> freed.incrementAndGet()));
        assertEquals(1, freed.get());
        assertTrue(coordinator.isPending("world"));
    }

    @Test void runningNativeLoadIsNotCancelledAndItsLaterHandleRemainsReclaimable() throws Exception {
        var coordinator = new ModelLoadCoordinator();
        var future = new CompletableFuture<ModelLoadCoordinator.AsyncLoadResult>();
        pending(coordinator).put("preview", future);
        assertFalse(coordinator.discardCompleted("preview", r -> fail("Handle not ready")));
        assertFalse(future.isCancelled());
        assertTrue(coordinator.isPending("preview"));
        future.complete(new ModelLoadCoordinator.AsyncLoadResult(9L, null, "preview"));
        var freed = new AtomicInteger();
        assertTrue(coordinator.discardCompleted("preview", r -> freed.incrementAndGet()));
        assertEquals(1, freed.get());
        assertFalse(coordinator.isPending("preview"));
    }
}
