package com.shiroha.mmdskin.compat.vr.pointer;

import com.shiroha.mmdskin.config.VrPointerConfigData;

/** The hit mode is evaluated for each hand, independently of the other hand's UI intersection. */
public final class VrPointerVisibility {
    private VrPointerVisibility() {}

    public static boolean visible(VrPointerConfigData.Visibility mode, boolean tracked, boolean enabled,
                                  boolean uiOpen, boolean uiHit) {
        if (!tracked || !enabled || mode == null) return false;
        return switch (mode) {
            case ALWAYS -> true;
            case UI_ONLY -> uiOpen;
            case UI_HIT -> uiHit;
            case OFF -> false;
        };
    }
}
