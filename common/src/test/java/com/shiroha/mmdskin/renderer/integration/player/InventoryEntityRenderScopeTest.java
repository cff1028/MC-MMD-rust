package com.shiroha.mmdskin.renderer.integration.player;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class InventoryEntityRenderScopeTest {
    @Test void scopeIdentifiesOnlyTheActualEntityAndEndsAfterExceptionalDraw() {
        Object player = new Object();
        Object anotherPlayer = new Object();
        assertFalse(InventoryEntityRenderScope.isActive());
        assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = InventoryEntityRenderScope.enter(player)) {
                assertTrue(InventoryEntityRenderScope.isRendering(player));
                assertFalse(InventoryEntityRenderScope.isRendering(anotherPlayer));
                throw new IllegalArgumentException("simulated renderer failure");
            }
        });
        assertFalse(InventoryEntityRenderScope.isActive());
        assertFalse(InventoryEntityRenderScope.isRendering(player));
    }

    @Test void nestedPreviewRestoresItsParentWithoutLeakingToOtherThreads() {
        Object parent = new Object(), child = new Object();
        try (var ignored = InventoryEntityRenderScope.enter(parent)) {
            assertFalse(CompletableFuture.supplyAsync(InventoryEntityRenderScope::isActive).join());
            try (var inner = InventoryEntityRenderScope.enter(child)) {
                assertTrue(InventoryEntityRenderScope.isRendering(child));
                assertFalse(InventoryEntityRenderScope.isRendering(parent));
            }
            assertTrue(InventoryEntityRenderScope.isRendering(parent));
        }
        assertFalse(InventoryEntityRenderScope.isActive());
    }

    @Test void invalidCloseCannotSilentlyClearTheCurrentPreview() {
        Object entity = new Object();
        var outer = InventoryEntityRenderScope.enter(entity);
        var inner = InventoryEntityRenderScope.enter(entity);
        try {
            assertThrows(IllegalStateException.class, outer::close);
            assertTrue(InventoryEntityRenderScope.isRendering(entity));
        } finally {
            inner.close();
            outer.close();
        }
        outer.close();
        assertFalse(InventoryEntityRenderScope.isActive());
    }

    @Test void previewCacheCannotShareWorldHandleAndStillMatchesPlayerReloadSuffix() {
        String player = "681f539b-8bb8-3f85-85e5-a2945f6c6539";
        String preview = InventoryEntityRenderScope.previewCacheKey(player);
        assertNotEquals(player, preview);
        assertTrue(("avatar_" + preview).endsWith("_" + player));
        assertEquals(preview, InventoryEntityRenderScope.previewCacheKey(player));
        assertNotEquals(preview, InventoryEntityRenderScope.previewCacheKey("another-player"));
    }
}
