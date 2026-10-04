package com.shiroha.mmdskin.ui.spatial.lumen.input;

/** Captured scrollbar/content gestures in logical panel pixels; independent of rendering and frame rate. */
public final class ScrollGesture {
    private float offset, velocity, maximum, extent, startX, startY, previousY, rawOffset, thumbGrab;
    private boolean candidate, dragging, barDragging, consumed;

    public float offset() { return offset; }
    public float maximum() { return maximum; }
    public boolean consumesPointer() { return consumed || dragging || barDragging; }
    public float thumbSize() { return maximum <= 0 ? extent : Math.max(36, extent * extent / (maximum + extent)); }
    public float thumbTop() { return maximum <= 0 ? 0 : (extent - thumbSize()) * Math.clamp(offset / maximum, 0, 1); }

    public void reset() { offset = velocity = 0; cancel(); }
    public void cancel() { candidate = dragging = barDragging = consumed = false; velocity = 0; }

    /** A slider takes precedence over the pending page swipe on its initial press. */
    public void captureControl() { candidate = false; velocity = 0; }

    public void update(InputState input, float left, float top, float width, float height, float content,
                       float trackX, float delta, boolean enabled, boolean controlCaptured, boolean reducedMotion) {
        extent = Math.max(1, height); maximum = Math.max(0, content - extent); consumed = false;
        float dt = Float.isFinite(delta) ? Math.clamp(delta, 0, .1f) : 0;
        boolean valid = Float.isFinite(input.x) && Float.isFinite(input.y) && Math.abs(input.x) < 9000 && Math.abs(input.y) < 9000;
        if (!enabled || controlCaptured || !valid) {
            consumed = dragging || barDragging; candidate = dragging = barDragging = false; velocity = 0;
            settle(dt, true); return;
        }
        if (maximum == 0) { offset = 0; cancel(); return; }
        boolean contentHit = input.inside(left, top, width, height);
        boolean trackHit = input.inside(trackX - 14, top, 30, height);
        if (input.pressed) {
            cancel();
            if (trackHit) {
                barDragging = consumed = true;
                float localY = input.y - top, thumb = thumbSize(), start = thumbTop();
                thumbGrab = localY >= start && localY <= start + thumb ? localY - start : thumb / 2;
            } else if (contentHit) {
                candidate = true; startX = input.x; startY = previousY = input.y; rawOffset = unresist(offset);
            }
        }
        if (barDragging) {
            consumed = true;
            offset = Math.clamp((input.y - top - thumbGrab) / Math.max(1, extent - thumbSize()), 0, 1) * maximum;
            velocity = 0;
        } else if (candidate && (input.down || input.released)) {
            if (!dragging && Math.abs(input.y - startY) >= 8 && Math.abs(input.y - startY) >= Math.abs(input.x - startX) * .8f) {
                dragging = true; previousY = startY;
            }
            if (dragging) {
                consumed = true;
                float before = offset;
                rawOffset -= input.y - previousY;
                offset = reducedMotion ? Math.clamp(rawOffset, 0, maximum) : resist(rawOffset);
                if (dt > 0) {
                    float sample = Math.clamp((offset - before) / dt, -2500, 2500);
                    velocity += (sample - velocity) * (1 - (float)Math.exp(-24 * dt));
                }
                previousY = input.y;
            }
        }
        if (input.released || !input.down && !input.pressed) {
            consumed |= dragging || barDragging;
            candidate = dragging = barDragging = false;
        }
        if (!candidate && !barDragging) {
            if ((contentHit || trackHit) && input.scroll != 0) {
                velocity = 0; offset = Math.clamp(offset - input.scroll * 43, 0, maximum);
            } else settle(dt, reducedMotion);
        }
    }

    private float limit() { return Math.min(88, extent * .23f); }
    private float resist(float raw) {
        float edge = raw < 0 ? 0 : maximum;
        if (raw >= 0 && raw <= maximum) return raw;
        float distance = raw - edge;
        return edge + distance * limit() / (limit() + Math.abs(distance));
    }
    private float unresist(float visible) {
        if (visible >= 0 && visible <= maximum) return visible;
        float edge = visible < 0 ? 0 : maximum, distance = visible - edge;
        return edge + distance * limit() / Math.max(1, limit() - Math.abs(distance));
    }
    private void settle(float dt, boolean reducedMotion) {
        if (reducedMotion) { offset = Math.clamp(offset, 0, maximum); velocity = 0; return; }
        // Small time slices keep the transition between free inertia and the boundary spring stable.
        while (dt > 0) {
            float step = Math.min(dt, 1f / 120); dt -= step;
            if (offset < 0 || offset > maximum) {
                float edge = offset < 0 ? 0 : maximum, distance = offset - edge, omega = 18;
                float term = velocity + omega * distance, decay = (float)Math.exp(-omega * step);
                offset = edge + (distance + term * step) * decay;
                velocity = (velocity - omega * term * step) * decay;
                if (Math.abs(offset - edge) < .08f && Math.abs(velocity) < .8f) { offset = edge; velocity = 0; }
            } else {
                float next = velocity * (float)Math.exp(-6 * step);
                offset += (velocity - next) / 6; velocity = Math.abs(next) < .5f ? 0 : next;
            }
        }
    }
}
