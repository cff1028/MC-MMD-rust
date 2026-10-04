package com.shiroha.mmdskin.ui.spatial.lumen.render;

import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import com.shiroha.mmdskin.ui.spatial.lumen.model.MenuBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;

import static org.lwjgl.nanovg.NanoVG.*;

/** Small NanoVG drawing surface. All positions use a 1440 × 900 logical panel. */
public final class Canvas {
    public static final int WIDTH = 1440;
    public static final int HEIGHT = 900;
    public final long vg;
    private final int regularFont;
    private final int boldFont;
    private final LinkedHashMap<String, CachedImage> images = new LinkedHashMap<>(16, .75f, true);
    private long imageBytes;
    private record CachedImage(int handle, int bytes) {}

    public Canvas(long vg) {
        this.vg = vg;
        regularFont = loadFont("ui", false);
        int bold = loadFont("ui-bold", true);
        boldFont = bold < 0 ? regularFont : bold;
        if (regularFont < 0) {
            throw new IllegalStateException("No usable font. Set -Dlumen.font=C:/path/to/a/CJK-font.ttf");
        }
    }

    private int loadFont(String name, boolean bold) {
        List<String> candidates = new ArrayList<>();
        String custom = System.getProperty(bold ? "lumen.font.bold" : "lumen.font");
        if (custom != null && !custom.isBlank()) candidates.add(custom);
        String windir = System.getenv().getOrDefault("WINDIR", "C:/Windows");
        candidates.add(windir + (bold ? "/Fonts/msyhbd.ttc" : "/Fonts/msyh.ttc"));
        candidates.add(windir + (bold ? "/Fonts/segoeuib.ttf" : "/Fonts/segoeui.ttf"));
        candidates.add("/System/Library/Fonts/PingFang.ttc");
        candidates.add("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");
        candidates.add("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
        for (String candidate : candidates) {
            if (Files.isRegularFile(Path.of(candidate))) {
                int font = nvgCreateFontAtIndex(vg, name, candidate, 0);
                if (font >= 0) return font;
            }
        }
        return -1;
    }

    public void fill(float x, float y, float w, float h, float r, int argb) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, w, h, r);
            nvgFillColor(vg, color(stack, argb));
            nvgFill(vg);
        }
    }

    public void stroke(float x, float y, float w, float h, float r, int argb, float width) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, w, h, r);
            nvgStrokeColor(vg, color(stack, argb));
            nvgStrokeWidth(vg, width);
            nvgStroke(vg);
        }
    }

    public void text(String text, float x, float y, float size, int argb) {
        drawText(text, x, y, size, argb, regularFont, NVG_ALIGN_LEFT);
    }

    public void textBold(String text, float x, float y, float size, int argb) {
        drawText(text, x, y, size, argb, boldFont, NVG_ALIGN_LEFT);
    }

    /** Restrained label weight; avoids artificially thickening CJK glyphs. */
    public void textMedium(String text, float x, float y, float size, int argb) {
        drawText(text, x, y, size, argb, regularFont, NVG_ALIGN_LEFT);
    }

    public void textCenter(String text, float x, float y, float size, int argb) {
        drawText(text, x, y, size, argb, regularFont, NVG_ALIGN_CENTER);
    }

    private void drawText(String text, float x, float y, float size, int argb, int font, int align) {
        if (text == null || text.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            nvgFontFaceId(vg, font);
            nvgFontSize(vg, size);
            nvgTextAlign(vg, align | NVG_ALIGN_TOP);
            nvgFillColor(vg, color(stack, argb));
            nvgText(vg, x, y, text);
        }
    }

    public float width(String text, float size) {
        nvgFontFaceId(vg, regularFont);
        nvgFontSize(vg, size);
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
        return nvgTextBounds(vg, 0, 0, text, (float[]) null);
    }

    public void line(float x1, float y1, float x2, float y2, int argb, float width) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            nvgBeginPath(vg);
            nvgMoveTo(vg, x1, y1);
            nvgLineTo(vg, x2, y2);
            nvgStrokeWidth(vg, width);
            nvgStrokeColor(vg, color(stack, argb));
            nvgLineCap(vg, NVG_ROUND);
            nvgStroke(vg);
        }
    }

    public void circle(float x, float y, float radius, int argb) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            nvgBeginPath(vg);
            nvgCircle(vg, x, y, radius);
            nvgFillColor(vg, color(stack, argb));
            nvgFill(vg);
        }
    }

    public void gradient(float x, float y, float w, float h, float r, int top, int bottom) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGPaint paint = NVGPaint.malloc(stack);
            nvgLinearGradient(vg, x, y, x, y + h, color(stack, top), color(stack, bottom), paint);
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, w, h, r);
            nvgFillPaint(vg, paint);
            nvgFill(vg);
        }
    }

    /** Draw a real cached resource with cover cropping; skin faces retain their pixel-art sampling. */
    public boolean image(MenuBackend.ImageData source, float x, float y, float width, float height, float radius, boolean pixelated) {
        if (source == null || source.width() <= 0 || source.height() <= 0 || source.width() > 1024 || source.height() > 1024
                || source.rgba() == null || source.rgba().length != source.width() * source.height() * 4 || width <= 0 || height <= 0) return false;
        String key = source.key() + (pixelated ? "#nearest" : "#linear");
        CachedImage cached = images.get(key);
        if (cached == null) {
            var pixels = MemoryUtil.memAlloc(source.rgba().length);
            try {
                pixels.put(source.rgba()).flip();
                int handle = nvgCreateImageRGBA(vg, source.width(), source.height(), pixelated ? NVG_IMAGE_NEAREST : 0, pixels);
                if (handle == 0) return false;
                cached = new CachedImage(handle, source.rgba().length); images.put(key, cached); imageBytes += cached.bytes();
            } finally { MemoryUtil.memFree(pixels); }
        }
        float scale = Math.max(width / source.width(), height / source.height());
        float imageWidth = source.width() * scale, imageHeight = source.height() * scale;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGPaint paint = NVGPaint.malloc(stack);
            nvgImagePattern(vg, x + (width - imageWidth) / 2, y + (height - imageHeight) / 2,
                    imageWidth, imageHeight, 0, cached.handle(), 1, paint);
            nvgBeginPath(vg); nvgRoundedRect(vg, x, y, width, height, radius); nvgFillPaint(vg, paint); nvgFill(vg);
        }
        return true;
    }

    /** Call after NanoVG submits the frame: never free a texture still referenced by its draw batch. */
    public void finishFrame() {
        var oldest = images.entrySet().iterator();
        while ((images.size() > 128 || imageBytes > 16 * 1024 * 1024) && oldest.hasNext()) {
            var entry = oldest.next(); nvgDeleteImage(vg, entry.getValue().handle()); imageBytes -= entry.getValue().bytes(); oldest.remove();
        }
    }

    /** Soft outer shadow. Its hollow centre does not darken a translucent panel's interior. */
    public void shadow(float x, float y, float w, float h, float r, float blur, float offsetY, int argb) {
        if (blur <= 0 || (argb >>> 24) == 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGPaint paint = NVGPaint.malloc(stack);
            nvgBoxGradient(vg, x, y+offsetY, w, h, r, blur, color(stack,argb), color(stack,argb & 0x00ffffff), paint);
            nvgBeginPath(vg);
            nvgRect(vg,x-blur*2,y+offsetY-blur*2,w+blur*4,h+blur*4);
            nvgRoundedRect(vg,x,y,w,h,r);
            nvgPathWinding(vg,NVG_HOLE);
            nvgFillPaint(vg,paint);
            nvgFill(vg);
        }
    }

    public static NVGColor color(MemoryStack stack, int argb) {
        return NVGColor.malloc(stack).r(((argb >>> 16) & 255) / 255f)
            .g(((argb >>> 8) & 255) / 255f).b((argb & 255) / 255f).a(((argb >>> 24) & 255) / 255f);
    }

    public void save() { nvgSave(vg); }
    public void restore() { nvgRestore(vg); }
    public void clip(float x, float y, float w, float h) { nvgIntersectScissor(vg, x, y, w, h); }
    public void alpha(float alpha) { nvgGlobalAlpha(vg, alpha); }
    public void translate(float x, float y) { nvgTranslate(vg, x, y); }
    public void scale(float amount) { nvgScale(vg, amount, amount); }
}
