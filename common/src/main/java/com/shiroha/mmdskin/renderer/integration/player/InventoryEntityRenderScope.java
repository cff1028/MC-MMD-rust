package com.shiroha.mmdskin.renderer.integration.player;

import java.util.Objects;

/** Marks the actual GUI entity draw, never all world draws while an inventory is open. */
public final class InventoryEntityRenderScope {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private InventoryEntityRenderScope() {}

    public static Scope enter(Object entity) {
        Scope scope = new Scope(Objects.requireNonNull(entity), CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static boolean isActive() { return CURRENT.get() != null; }

    public static boolean isRendering(Object entity) {
        Scope scope = CURRENT.get();
        return scope != null && scope.entity == entity;
    }

    /** Keep the player suffix so player reloads also retire this independent instance. */
    public static String previewCacheKey(String entityCacheKey) {
        return "inventory_preview_" + Objects.requireNonNull(entityCacheKey);
    }

    public static final class Scope implements AutoCloseable {
        private final Object entity;
        private final Scope previous;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private Scope(Object entity, Scope previous) {
            this.entity = entity;
            this.previous = previous;
        }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner || CURRENT.get() != this) {
                throw new IllegalStateException("Inventory preview scopes must close on their owner thread in reverse order");
            }
            closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
