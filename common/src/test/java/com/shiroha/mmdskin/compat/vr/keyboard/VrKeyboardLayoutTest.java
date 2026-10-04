package com.shiroha.mmdskin.compat.vr.keyboard;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VrKeyboardLayoutTest {
    @Test void allPagesHaveUniqueReachableNonOverlappingKeysInsideThePanel() {
        for (var page : VrKeyboardLayout.Page.values()) for (boolean shift : new boolean[]{false, true}) {
            List<VrKeyboardLayout.Key> keys = VrKeyboardLayout.keys(page, shift);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < keys.size(); i++) {
                var key = keys.get(i);
                assertTrue(ids.add(key.id()), "Duplicate key id: " + key.id());
                assertTrue(key.x() >= 0 && key.y() >= 0 && key.width() > 0 && key.height() > 0);
                assertTrue(key.x() + key.width() <= VrKeyboardLayout.WIDTH);
                assertTrue(key.y() + key.height() <= VrKeyboardLayout.HEIGHT);
                for (int j = 0; j < i; j++) assertFalse(intersects(key, keys.get(j)),
                        page + " overlapping keys: " + key.id() + " / " + keys.get(j).id());
            }
            assertTrue(ids.containsAll(Set.of("page", "language", "space", "backspace", "enter", "left", "right", "settings", "close")));
            assertThrows(UnsupportedOperationException.class, () -> keys.add(keys.getFirst()));
        }
    }

    @Test void normalizedHitsFollowTheActualScaledPanelAtEveryScreenAspect() {
        for (int[] size : new int[][]{{320, 240}, {640, 360}, {1100, 510}, {1920, 1080}, {1024, 2048}}) {
            var transform = VrKeyboardLayout.transform(size[0], size[1]);
            assertTrue(transform.x() >= 0 && transform.y() >= 0 && transform.scale() > 0);
            for (var page : VrKeyboardLayout.Page.values()) {
                var keys = VrKeyboardLayout.keys(page, false);
                for (var key : keys) {
                    double centerX = key.x() + key.width() / 2.0, centerY = key.y() + key.height() / 2.0;
                    assertEquals(key.id(), hitLocal(keys, centerX, centerY, size));
                    assertEquals(key.id(), hitLocal(keys, key.x() + .001, key.y() + .001, size));
                    assertEquals(key.id(), hitLocal(keys, key.x() + key.width() - .001, key.y() + key.height() - .001, size));
                    double u = (transform.x() + centerX * transform.scale()) / size[0];
                    double v = (transform.y() + centerY * transform.scale()) / size[1];
                    assertEquals(centerX, transform.localX(u, size[0]), 1e-8);
                    assertEquals(centerY, transform.localY(v, size[1]), 1e-8);
                }
                assertNull(hitLocal(keys, 113, 150, size), "Gap between the first two keys must not type");
                assertNull(hitLocal(keys, 550, 95, size), "Composition area must not count as a key");
                assertNull(hitLocal(keys, 5, 240, size), "Panel edge must not count as a key");
                assertNull(hitLocal(keys, 550, 500, size), "Panel footer must not count as a key");
            }
        }
    }

    @Test void edgesDoNotOverlapAndInvalidRayCoordinatesCannotType() {
        var keys = VrKeyboardLayout.keys(VrKeyboardLayout.Page.LETTERS, false);
        var q = keys.getFirst();
        assertTrue(q.contains(q.x(), q.y()));
        assertFalse(q.contains(q.x() + q.width(), q.y()));
        assertFalse(q.contains(q.x(), q.y() + q.height()));
        for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1, 2}) {
            assertNull(VrKeyboardLayout.hit(keys, invalid, .5, 1100, 510));
            assertNull(VrKeyboardLayout.hit(keys, .5, invalid, 1100, 510));
        }
        assertNull(VrKeyboardLayout.hit(keys, .5, .5, 0, 510));
        assertNull(VrKeyboardLayout.hit(keys, .5, .5, 1100, -1));
    }

    @Test void letterShiftKeepsHitTargetsStableAndAllPrintableAsciiCharactersAreAvailable() {
        var lower = VrKeyboardLayout.keys(VrKeyboardLayout.Page.LETTERS, false);
        var upper = VrKeyboardLayout.keys(VrKeyboardLayout.Page.LETTERS, true);
        assertEquals(lower.size(), upper.size());
        Set<Integer> available = new HashSet<>();
        available.add((int)' ');
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < lower.size(); i++) {
            var a = lower.get(i);
            var b = upper.get(i);
            assertEquals(a.id(), b.id());
            assertEquals(a.x(), b.x()); assertEquals(a.y(), b.y());
            assertEquals(a.width(), b.width()); assertEquals(a.height(), b.height());
            if (a.id().startsWith("letter:")) {
                letters.append(a.label());
                assertEquals(a.label().toUpperCase(java.util.Locale.ROOT), b.label());
                a.label().codePoints().forEach(available::add);
                b.label().codePoints().forEach(available::add);
            }
        }
        assertEquals("qwertyuiopasdfghjklzxcvbnm", letters.toString());
        for (var page : VrKeyboardLayout.Page.values()) {
            for (var key : VrKeyboardLayout.keys(page, false)) {
                if (key.id().startsWith("text:")) key.id().substring(5).codePoints().forEach(available::add);
            }
        }
        for (int c = 32; c < 127; c++) assertTrue(available.contains(c), "Unreachable printable ASCII character: " + (char)c);
    }

    private static String hitLocal(List<VrKeyboardLayout.Key> keys, double x, double y, int[] size) {
        var t = VrKeyboardLayout.transform(size[0], size[1]);
        return VrKeyboardLayout.hit(keys, (t.x() + x * t.scale()) / size[0], (t.y() + y * t.scale()) / size[1], size[0], size[1]);
    }

    private static boolean intersects(VrKeyboardLayout.Key a, VrKeyboardLayout.Key b) {
        return a.x() < b.x() + b.width() && a.x() + a.width() > b.x()
                && a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
    }
}
