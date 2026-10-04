package com.shiroha.mmdskin.config;

/** Global VR pointer preferences. Beam distances are metres, dot radii are fractions of each eye's view height. */
public final class VrPointerConfigData {
    public enum Visibility { ALWAYS, UI_ONLY, UI_HIT, OFF }
    public enum PressBinding { TRIGGER, GRIP, TRIGGER_OR_GRIP }
    public enum CrosshairMode { OFF, UI_ONLY, UI_HIT, ALWAYS }

    public Visibility visibility = Visibility.UI_ONLY;
    public boolean leftEnabled = true;
    public boolean leftTriggerInteraction = false;
    public boolean rightEnabled = true;
    public boolean dotEnabled = true;
    public CrosshairMode hideCrosshairMode = CrosshairMode.OFF;
    public PressBinding pressBinding = PressBinding.TRIGGER;
    public int rayColor = 0x66CCFF;
    public float rayAlpha = .65f;
    /** Full beam diameter, not radius. */
    public float rayWidth = .002f;
    public float maxDistance = 8f;
    public int dotColor = 0xFFFFFF;
    public float dotAlpha = .9f;
    /** Radius as a fraction of the per-eye view height, independent of hit distance. */
    public float dotRadiusView = .003f;
    public int pressedRayColor = 0xFFAA33;
    public int pressedDotColor = 0xFFAA33;
    public boolean colorAnimationEnabled = true;
    public float colorDurationMs = 120f;
    public float pressedDotRadiusView = .0045f;
    public boolean radiusAnimationEnabled = true;
    public float radiusDurationMs = 120f;
    public float fadeInMs = 100f;
    public float fadeOutMs = 120f;

    public VrPointerConfigData copy() {
        VrPointerConfigData copy = new VrPointerConfigData();
        copy.visibility = visibility;
        copy.leftEnabled = leftEnabled;
        copy.leftTriggerInteraction = leftTriggerInteraction;
        copy.rightEnabled = rightEnabled;
        copy.dotEnabled = dotEnabled;
        copy.hideCrosshairMode = hideCrosshairMode;
        copy.pressBinding = pressBinding;
        copy.rayColor = rayColor;
        copy.rayAlpha = rayAlpha;
        copy.rayWidth = rayWidth;
        copy.maxDistance = maxDistance;
        copy.dotColor = dotColor;
        copy.dotAlpha = dotAlpha;
        copy.dotRadiusView = dotRadiusView;
        copy.pressedRayColor = pressedRayColor;
        copy.pressedDotColor = pressedDotColor;
        copy.colorAnimationEnabled = colorAnimationEnabled;
        copy.colorDurationMs = colorDurationMs;
        copy.pressedDotRadiusView = pressedDotRadiusView;
        copy.radiusAnimationEnabled = radiusAnimationEnabled;
        copy.radiusDurationMs = radiusDurationMs;
        copy.fadeInMs = fadeInMs;
        copy.fadeOutMs = fadeOutMs;
        return copy;
    }

    public VrPointerConfigData normalizedCopy() { return copy().normalizeInPlace(); }

    public VrPointerConfigData normalizeInPlace() {
        if (visibility == null) visibility = Visibility.UI_ONLY;
        if (pressBinding == null) pressBinding = PressBinding.TRIGGER;
        if (hideCrosshairMode == null) hideCrosshairMode = CrosshairMode.OFF;
        rayColor &= 0xFFFFFF;
        dotColor &= 0xFFFFFF;
        pressedRayColor &= 0xFFFFFF;
        pressedDotColor &= 0xFFFFFF;
        rayAlpha = bounded(rayAlpha, 0, 1, .65f);
        dotAlpha = bounded(dotAlpha, 0, 1, .9f);
        rayWidth = bounded(rayWidth, .0005f, .02f, .002f);
        maxDistance = bounded(maxDistance, .25f, 32f, 8f);
        dotRadiusView = bounded(dotRadiusView, .0005f, .025f, .003f);
        pressedDotRadiusView = bounded(pressedDotRadiusView, .0005f, .025f, .0045f);
        colorDurationMs = bounded(colorDurationMs, 0, 2000, 120);
        radiusDurationMs = bounded(radiusDurationMs, 0, 2000, 120);
        fadeInMs = bounded(fadeInMs, 0, 2000, 100);
        fadeOutMs = bounded(fadeOutMs, 0, 2000, 120);
        return this;
    }

    private static float bounded(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
