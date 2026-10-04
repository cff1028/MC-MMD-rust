package com.shiroha.mmdskin.ui.spatial.render;

import com.shiroha.mmdskin.ui.selector.VrControllerDebugScreen;
import com.shiroha.mmdskin.ui.spatial.SpatialMenuHost;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayDeque;

/** Font selection belongs to one screen draw, including nested overlays and failures. */
public final class SpatialMenuFontScope implements AutoCloseable {
    private static final ThreadLocal<ArrayDeque<SpatialMenuFontScope>> SCOPES =
        ThreadLocal.withInitial(ArrayDeque::new);
    private final boolean enabled;
    private boolean closed;

    private SpatialMenuFontScope(Screen screen) {
        enabled = SpatialMenuHost.isNativeSession() && !(screen instanceof VrControllerDebugScreen);
        SCOPES.get().push(this);
        if (enabled) SpatialMenuHost.beginNativeFont();
    }

    public static SpatialMenuFontScope forScreen(Screen screen) { return new SpatialMenuFontScope(screen); }

    public static boolean active() {
        if (!SpatialMenuHost.nativeFontActive()) return false;
        var scopes = SCOPES.get();
        return scopes.isEmpty() || scopes.peek().enabled;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { if (enabled) SpatialMenuHost.endNativeFont(); }
        finally {
            var scopes = SCOPES.get();
            scopes.removeFirstOccurrence(this);
            if (scopes.isEmpty()) SCOPES.remove();
        }
    }
}
