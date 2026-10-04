package com.shiroha.mmdskin.compat.vr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class VrRadialLayoutTest {
    @Test void actionsAndNavigationStayInsideSmallAndLargeScreensWithoutOverlaps() {
        for (int[] screen : new int[][]{{200, 140}, {256, 192}, {320, 240}, {640, 360}, {1280, 720}}) {
            for (int preferredWidth : new int[]{120, 220, 340}) {
                var layout = VrRadialLayout.create(screen[0], screen[1], preferredWidth);
                var bounds = new ArrayList<>(layout.actions());
                bounds.add(layout.previous());
                bounds.add(layout.next());
                assertEquals(9, bounds.size());
                for (int i = 0; i < bounds.size(); i++) {
                    var button = bounds.get(i);
                    assertTrue(button.x() >= 0 && button.y() >= 0);
                    assertTrue(button.right() <= screen[0] && button.bottom() <= screen[1]);
                    assertTrue(button.width() > 0 && button.height() >= 12);
                    for (int j = 0; j < i; j++) assertFalse(intersects(button, bounds.get(j)),
                            "Overlapping targets at " + screen[0] + "x" + screen[1] + ": " + button + ", " + bounds.get(j));
                }
                assertTrue(layout.titleY() >= 0 && layout.titleY() + 10 < bounds.getFirst().y());
                assertTrue(layout.hintY() + 9 <= screen[1]);
            }
        }
    }

    @Test void ringLeavesTheSelectionCenterClearAndKeepsSymmetricLeftRightTargets() {
        var layout = VrRadialLayout.create(640, 360, 160);
        var actions = layout.actions();
        assertEquals(7, actions.size());
        int centerX = 320, centerY = actions.get(2).y() + actions.get(2).height() / 2;
        for (var button : actions) {
            assertFalse(centerX >= button.x() && centerX < button.right() && centerY >= button.y() && centerY < button.bottom());
        }
        for (int i = 1; i <= 3; i++) {
            var right = actions.get(i);
            var left = actions.get(7 - i);
            assertEquals(right.y(), left.y());
            assertEquals(640 - right.right(), left.x());
        }
        assertEquals(320, actions.getFirst().x() + actions.getFirst().width() / 2);
        assertTrue(layout.previous().y() > actions.get(3).bottom());
    }

    private static boolean intersects(VrRadialLayout.Bounds a, VrRadialLayout.Bounds b) {
        return a.x() < b.right() && a.right() > b.x() && a.y() < b.bottom() && a.bottom() > b.y();
    }
}
