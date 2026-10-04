package com.shiroha.mmdskin.compat.vr;

import java.util.ArrayList;
import java.util.List;

/** Eight-position Vivecraft-style ring: seven actions and paging at the bottom. */
final class VrRadialLayout {
    private static final int[] ACTION_SLOTS = {0, 1, 2, 3, 5, 6, 7};

    private VrRadialLayout() {}

    record Bounds(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
    }

    record Layout(List<Bounds> actions, Bounds previous, Bounds next, int titleY, int hintY) {}

    static Layout create(int screenWidth, int screenHeight, int preferredWidth) {
        int margin = Math.clamp(screenWidth / 40, 2, 8);
        int height = Math.clamp((screenHeight - 38) / 5 - 4, 12, 22);
        int gap = Math.clamp((screenHeight - 40 - 5 * height) / 4, 2, 6);
        int pitch = height + gap;
        int top = Math.max(20, (screenHeight - (4 * pitch + height) - 16) / 2);
        int width = Math.min(preferredWidth, Math.max(24, (screenWidth - 3 * margin - 12) / 2));
        int centerX = screenWidth / 2;
        int maximumOffset = (screenWidth - 2 * margin - width) / 2;
        int radiusX = Math.min(maximumOffset, 32 + width / 2);
        // Vivecraft lays rows on an ellipse and pulls the diagonal buttons slightly inward.
        int diagonalX = Math.min(maximumOffset, Math.max((width + gap) / 2,
                (int)(radiusX * Math.sqrt(.75) * .87)));
        List<Bounds> actions = new ArrayList<>(ACTION_SLOTS.length);
        for (int slot : ACTION_SLOTS) {
            int row = slot <= 4 ? slot : 8 - slot;
            int offset = slot == 0 ? 0 : (slot == 2 || slot == 6 ? radiusX : diagonalX);
            if (slot > 4) offset = -offset;
            actions.add(new Bounds(centerX + offset - width / 2, top + row * pitch, width, height));
        }
        int navigationWidth = Math.min(110, (screenWidth - 2 * margin - gap) / 2);
        int navigationX = centerX - (2 * navigationWidth + gap) / 2;
        int navigationY = top + 4 * pitch;
        return new Layout(List.copyOf(actions),
                new Bounds(navigationX, navigationY, navigationWidth, height),
                new Bounds(navigationX + navigationWidth + gap, navigationY, navigationWidth, height),
                top - 16, navigationY + height + 6);
    }
}
