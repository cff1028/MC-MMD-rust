package com.shiroha.mmdskin.compat.vr.keyboard;

import java.util.ArrayList;
import java.util.List;

/** One shared layout for rendering, ray selection and physical contact. */
public final class VrKeyboardLayout {
    public static final int WIDTH = 1100, HEIGHT = 510;
    public enum Page { LETTERS, NUMBERS, SYMBOLS }
    public record Key(String id, String label, String hint, int x, int y, int width, int height) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
    public record Transform(double scale, double x, double y) {
        public double localX(double u, int width) { return (u * width - x) / scale; }
        public double localY(double v, int height) { return (v * height - y) / scale; }
    }
    private VrKeyboardLayout() {}

    public static Transform transform(int width, int height) {
        double scale = Math.max(.01, Math.min(width / (double) WIDTH, height / (double) HEIGHT) * .96);
        return new Transform(scale, (width - WIDTH * scale) / 2, (height - HEIGHT * scale) / 2);
    }

    public static List<Key> keys(Page page, boolean shift) {
        List<Key> keys = new ArrayList<>();
        if (page == Page.LETTERS) {
            letters(keys, "qwertyuiop", "1234567890", 22, 118, shift);
            add(keys, "backspace", "⌫", "", 982, 118, 96);
            letters(keys, "asdfghjkl", "@#$&*()'\"", 46, 208, shift);
            add(keys, "enter", "Enter", "↵", 910, 208, 168);
            add(keys, "shift-left", "↑", "", 22, 298, 88);
            letters(keys, "zxcvbnm", "%-+=/;:", 118, 298, shift);
            add(keys, "text:,", ",", "!", 790, 298, 88);
            add(keys, "text:.", ".", "?", 886, 298, 88);
            add(keys, "shift-right", "↑", "", 982, 298, 96);
        } else {
            String[] row1 = page == Page.NUMBERS
                    ? new String[]{"1","2","3","4","5","6","7","8","9","0"}
                    : new String[]{"~","`","·","•","√","π","÷","×","¶","Δ"};
            String[] row2 = page == Page.NUMBERS
                    ? new String[]{"@","#","$","&","*","(",")","'","\""}
                    : new String[]{"£","¢","€","¥","^","°","=","{","}"};
            String[] row3 = page == Page.NUMBERS
                    ? new String[]{"%","-","+","=","/",";",":","!","?","\\"}
                    : new String[]{",","¿","¡","<",">","[","]","_","|","\\"};
            symbols(keys, row1, 22, 118);
            add(keys, "backspace", "⌫", "", 982, 118, 96);
            symbols(keys, row2, 46, 208);
            add(keys, "enter", "Enter", "↵", 910, 208, 168);
            add(keys, "more", page == Page.NUMBERS ? "~[<" : "!123", "", 22, 298, 88);
            symbols(keys, row3, 118, 298);
        }
        add(keys, "page", page == Page.LETTERS ? "!123" : "abc", "", 22, 388, 130);
        add(keys, "language", "IME", "", 160, 388, 104);
        add(keys, "space", "", "", 272, 388, 486);
        add(keys, "left", "←", "", 766, 388, 66);
        add(keys, "right", "→", "", 840, 388, 66);
        add(keys, "settings", "⚙", "", 914, 388, 74);
        add(keys, "close", "×", "", 996, 388, 82);
        return List.copyOf(keys);
    }

    private static void letters(List<Key> keys, String letters, String hints, int x, int y, boolean shift) {
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            add(keys, "letter:" + c, String.valueOf(shift ? Character.toUpperCase(c) : c),
                    String.valueOf(hints.charAt(i)), x + i * 96, y, 88);
        }
    }
    private static void symbols(List<Key> keys, String[] values, int x, int y) {
        for (int i = 0; i < values.length; i++) add(keys, "text:" + values[i], values[i], "", x + i * 96, y, 88);
    }
    private static void add(List<Key> keys, String id, String label, String hint, int x, int y, int width) {
        keys.add(new Key(id, label, hint, x, y, width, 80));
    }
    public static String hit(List<Key> keys, double u, double v, int width, int height) {
        if (!Double.isFinite(u) || !Double.isFinite(v) || width <= 0 || height <= 0) return null;
        Transform t = transform(width, height);
        double x = t.localX(u, width), y = t.localY(v, height);
        for (Key key : keys) if (key.contains(x, y)) return key.id;
        return null;
    }
}
