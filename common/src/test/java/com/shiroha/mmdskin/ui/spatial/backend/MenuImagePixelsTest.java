package com.shiroha.mmdskin.ui.spatial.backend;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuImagePixelsTest {
    @Test void combinesTransparentAndTranslucentHatWithBaseFace() {
        var face = MenuImagePixels.face(64, 64, (x, y) -> x >= 40 ? (y == 8 ? 0x800000FF : 0) : 0xFFFF0000);
        assertNotNull(face); assertEquals(8, face.width()); assertEquals(8, face.height());
        assertArrayEquals(new byte[]{127, 0, (byte)128, (byte)255}, java.util.Arrays.copyOf(face.rgba(), 4));
        int row = 8 * 4;
        assertEquals(255, face.rgba()[row] & 255); assertEquals(0, face.rgba()[row + 2] & 255);
    }
    @Test void highResolutionSkinsCropBothLayersWithoutExposingWholeSkin() {
        var face = MenuImagePixels.face(128, 128, (x, y) -> x >= 80 && y >= 16 ? 0xFF00FF00 : 0xFFFF0000);
        assertNotNull(face); assertEquals(16, face.width()); assertEquals(16, face.height());
        assertEquals(16 * 16 * 4, face.rgba().length);
        assertEquals(255, face.rgba()[1] & 255);
        assertNull(MenuImagePixels.face(32, 32, (x, y) -> 0));
    }
    @Test void contentKeysChangeWithPixelsAndRgbaChannelsRemainOrdered() {
        var first = MenuImagePixels.image(1, 1, (x, y) -> 0x12345678);
        var same = MenuImagePixels.image(1, 1, (x, y) -> 0x12345678);
        var next = MenuImagePixels.image(1, 1, (x, y) -> 0x12345679);
        assertArrayEquals(new byte[]{0x34, 0x56, 0x78, 0x12}, first.rgba());
        assertEquals(first.key(), same.key()); assertNotEquals(first.key(), next.key());
    }
}
