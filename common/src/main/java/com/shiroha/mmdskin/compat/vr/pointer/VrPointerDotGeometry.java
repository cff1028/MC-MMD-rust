package com.shiroha.mmdskin.compat.vr.pointer;

import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** A circular screen footprint projected onto the selected surface, without a world-size radius. */
public final class VrPointerDotGeometry {
    private VrPointerDotGeometry() {}

    public record Disc(Vector3d center, Vector3d[] inner, Vector3d[] outer) {}

    /** Inputs and output are eye-space coordinates. Radius is a fraction of viewport height. */
    public static Disc create(Vector3dc center, Vector3dc normal, Matrix4fc projection,
                              int viewportWidth, int viewportHeight, double radiusView) {
        if (!finite(center) || !finite(normal) || projection == null || !projection.isFinite()
                || viewportWidth <= 0 || viewportHeight <= 0 || !Double.isFinite(radiusView)
                || radiusView <= 0 || center.z() >= -1e-5
                || Math.abs(projection.m00()) < 1e-6 || Math.abs(projection.m11()) < 1e-6) return null;
        Vector3d n = new Vector3d(normal);
        if (n.lengthSquared() < 1e-12) return null;
        n.normalize();
        double planeDistance = n.dot(center);
        if (Math.abs(planeDistance) < 1e-8) return null;
        double radiusY = -2 * center.z() * radiusView / Math.abs(projection.m11());
        double radiusX = -2 * center.z() * radiusView * viewportHeight / viewportWidth / Math.abs(projection.m00());
        Vector3d[] inner = new Vector3d[48], outer = new Vector3d[48];
        for (int i = 0; i < outer.length; i++) {
            double angle = i * Math.PI * 2 / outer.length;
            double x = Math.cos(angle) * radiusX, y = Math.sin(angle) * radiusY;
            inner[i] = project(center, n, planeDistance, x * .85, y * .85);
            outer[i] = project(center, n, planeDistance, x, y);
            // A surface seen exactly edge-on cannot contain a finite circular screen footprint.
            if (inner[i] == null || outer[i] == null) return null;
        }
        return new Disc(new Vector3d(center), inner, outer);
    }

    private static Vector3d project(Vector3dc center, Vector3dc normal, double planeDistance, double x, double y) {
        Vector3d point = new Vector3d(center).add(x, y, 0);
        double denominator = normal.dot(point);
        if (Math.abs(denominator) < 1e-10) return null;
        double scale = planeDistance / denominator;
        if (!Double.isFinite(scale) || scale < .01 || scale > 100) return null;
        point.mul(scale);
        return finite(point) ? point : null;
    }

    private static boolean finite(Vector3dc value) {
        return value != null && Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }
}
