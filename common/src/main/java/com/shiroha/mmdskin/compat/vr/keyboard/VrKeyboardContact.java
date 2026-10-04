package com.shiroha.mmdskin.compat.vr.keyboard;

import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Keyboard hit coordinates in room metres; the positive Z side faces the typist. */
final class VrKeyboardContact {
    record Point(double u, double v, double depth) {
        boolean onPlane() { return u >= 0 && u <= 1 && v >= 0 && v <= 1; }
        boolean near(double distance) { return onPlane() && depth > -.06 && depth < distance; }
    }

    static Point project(Vector3fc point, Vector3fc center, Matrix4fc inverseRotation,
                         double width, double height) {
        if (point == null || center == null || inverseRotation == null
                || !Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) return null;
        Vector3f local = inverseRotation.transformDirection(new Vector3f(point).sub(center));
        if (!local.isFinite()) return null;
        return new Point(.5 + local.x / width, .5 - local.y / height, local.z);
    }

    private VrKeyboardContact() {}
}
