package com.shiroha.mmdskin.compat.vr.pointer;

import com.shiroha.mmdskin.config.VrPointerConfigData;

/** Interruptible pointer transitions, sampled once per VR frame independently for each hand. */
public final class VrPointerAnimation {
    private static final Settings DEFAULTS = Settings.fromConfig(null);
    private final Channel visibility = new Channel();
    private final Channel[] ray = {new Channel(), new Channel(), new Channel()};
    private final Channel[] dot = {new Channel(), new Channel(), new Channel()};
    private final Channel radius = new Channel();
    private boolean initialized;
    private long lastFrame, lastTime;

    /** Colors are RGB; radii are fractions of viewport height; durations are milliseconds. */
    public record Settings(int rayColor, int dotColor, int pressedRayColor, int pressedDotColor,
                           float dotRadius, float pressedDotRadius,
                           boolean colorAnimationEnabled, float colorDurationMs,
                           boolean radiusAnimationEnabled, float radiusDurationMs,
                           float fadeInMs, float fadeOutMs) {
        public static Settings fromConfig(VrPointerConfigData config) {
            VrPointerConfigData value = config == null ? new VrPointerConfigData() : config.normalizedCopy();
            return new Settings(value.rayColor, value.dotColor, value.pressedRayColor, value.pressedDotColor,
                    value.dotRadiusView, value.pressedDotRadiusView, value.colorAnimationEnabled, value.colorDurationMs,
                    value.radiusAnimationEnabled, value.radiusDurationMs, value.fadeInMs, value.fadeOutMs);
        }
    }

    /** Multiply visibility by the configured ray/dot alpha when drawing. */
    public record Sample(float visibility, int rayColor, int dotColor, float dotRadius) {}

    public Sample sample(long frameId, long nowNanos, boolean visible, boolean pressed, Settings settings) {
        Settings current = settings == null ? DEFAULTS : settings;
        float baseRadius = safeRadius(current.dotRadius, DEFAULTS.dotRadius);
        float pressedRadius = safeRadius(current.pressedDotRadius, DEFAULTS.pressedDotRadius);
        long now = initialized && (lastFrame == frameId || nowNanos < lastTime) ? lastTime : nowNanos;
        if (!initialized) {
            visibility.initialize(0, now);
            initializeColor(ray, current.rayColor, now);
            initializeColor(dot, current.dotColor, now);
            radius.initialize(baseRadius, now);
            initialized = true;
        }
        lastFrame = frameId;
        lastTime = now;

        visibility.retarget(visible ? 1 : 0, duration(visible ? current.fadeInMs : current.fadeOutMs,
                visible ? DEFAULTS.fadeInMs : DEFAULTS.fadeOutMs), now);
        long colorDuration = current.colorAnimationEnabled ? duration(current.colorDurationMs, DEFAULTS.colorDurationMs) : 0;
        color(ray, pressed ? current.pressedRayColor : current.rayColor, colorDuration, now);
        color(dot, pressed ? current.pressedDotColor : current.dotColor, colorDuration, now);
        radius.retarget(pressed ? pressedRadius : baseRadius,
                current.radiusAnimationEnabled ? duration(current.radiusDurationMs, DEFAULTS.radiusDurationMs) : 0, now);
        return new Sample(visibility.value(now), packedColor(ray, now), packedColor(dot, now), radius.value(now));
    }

    /** A new device/world starts without retaining the old hand's transitions. */
    public void reset() { initialized = false; }

    private static void initializeColor(Channel[] channels, int rgb, long now) {
        for (int i = 0; i < 3; i++) channels[i].initialize((rgb >>> ((2 - i) * 8)) & 255, now);
    }

    private static void color(Channel[] channels, int rgb, long duration, long now) {
        for (int i = 0; i < 3; i++) channels[i].retarget((rgb >>> ((2 - i) * 8)) & 255, duration, now);
    }

    private static int packedColor(Channel[] channels, long now) {
        int result = 0;
        for (Channel channel : channels) result = (result << 8) | Math.clamp(Math.round(channel.value(now)), 0, 255);
        return result;
    }

    private static float safeRadius(float value, float fallback) {
        return Float.isFinite(value) ? Math.clamp(value, 0, 10) : fallback;
    }

    private static long duration(float value, float fallback) {
        return Math.round((double) (Float.isFinite(value) ? Math.clamp(value, 0, 60_000) : fallback) * 1_000_000);
    }

    private static final class Channel {
        private float startValue, target;
        private long startTime, duration;

        void initialize(float value, long now) {
            startValue = target = value;
            startTime = now;
            duration = 0;
        }

        void retarget(float next, long nextDuration, long now) {
            if (nextDuration == 0) { initialize(next, now); return; }
            if (next == target && nextDuration == duration) return;
            startValue = value(now);
            target = next;
            startTime = now;
            duration = nextDuration;
        }

        float value(long now) {
            if (duration == 0) return target;
            // Convert before subtracting so unusually long gaps cannot overflow signed long.
            double amount = Math.clamp(((double) now - (double) startTime) / duration, 0, 1);
            return (float) (startValue + (target - startValue) * amount);
        }
    }
}
