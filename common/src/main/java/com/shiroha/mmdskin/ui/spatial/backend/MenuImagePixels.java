package com.shiroha.mmdskin.ui.spatial.backend;

import com.shiroha.mmdskin.ui.spatial.lumen.model.MenuBackend.ImageData;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.IntBinaryOperator;

/** CPU-only image representation shared with the UI; skin output contains only the composed face. */
final class MenuImagePixels {
    private MenuImagePixels() {}
    static ImageData image(int width, int height, IntBinaryOperator argb) {
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int color = argb.applyAsInt(x, y), at = (y * width + x) * 4;
            rgba[at] = (byte)(color >>> 16); rgba[at + 1] = (byte)(color >>> 8);
            rgba[at + 2] = (byte)color; rgba[at + 3] = (byte)(color >>> 24);
        }
        return new ImageData(width + "x" + height + ":" + contentKey(rgba), width, height, rgba);
    }
    static String contentKey(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static ImageData face(int width, int height, IntBinaryOperator argb) {
        if (width < 64 || width % 64 != 0 || height < width / 2 || width > 2048) return null;
        int unit = width / 64, size = 8 * unit;
        return image(size, size, (x, y) -> {
            int base = argb.applyAsInt(8 * unit + x, 8 * unit + y);
            int hat = argb.applyAsInt(40 * unit + x, 8 * unit + y);
            int alpha = hat >>> 24, inverse = 255 - alpha;
            int red = ((hat >>> 16 & 255) * alpha + (base >>> 16 & 255) * inverse + 127) / 255;
            int green = ((hat >>> 8 & 255) * alpha + (base >>> 8 & 255) * inverse + 127) / 255;
            int blue = ((hat & 255) * alpha + (base & 255) * inverse + 127) / 255;
            return 0xFF000000 | red << 16 | green << 8 | blue;
        });
    }
}
