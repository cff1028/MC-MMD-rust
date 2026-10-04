package com.shiroha.mmdskin.compat.vr.pointer;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Finite, double-sided UI rectangles. Distances and dimensions are in world metres. */
public final class VrPointerGeometry {
    private static final double EPSILON = 1.0e-9;
    private VrPointerGeometry() {}

    /** Basis inputs are copied; halfWidth/halfHeight remain physical dimensions after normalization. */
    public record Plane(String id, Vector3dc center, Vector3dc right, Vector3dc up,
                        double halfWidth, double halfHeight) {
        public Plane {
            center = center == null ? null : new Vector3d(center);
            right = right == null ? null : new Vector3d(right);
            up = up == null ? null : new Vector3d(up);
        }
    }

    /** u/v use a top-left origin: right increases u, up decreases v. */
    public record Hit(Plane plane, double distance, Vector3d position, double u, double v) {}

    public static Hit intersect(Vector3dc origin, Vector3dc direction, Plane plane, double maxDistance) {
        if (plane == null || !finite(origin) || !finite(direction) || !finite(plane.center)
                || !finite(plane.right) || !finite(plane.up) || !Double.isFinite(maxDistance) || maxDistance < 0
                || !positive(plane.halfWidth) || !positive(plane.halfHeight)) return null;
        Vector3d ray = new Vector3d(direction), right = new Vector3d(plane.right), up = new Vector3d(plane.up);
        if (!normalize(ray) || !normalize(right)) return null;
        up.sub(new Vector3d(right).mul(up.dot(right)));
        if (!normalize(up)) return null;
        Vector3d normal = new Vector3d(right).cross(up);
        double denominator = ray.dot(normal);
        if (Math.abs(denominator) < EPSILON) return null;
        double distance = new Vector3d(plane.center).sub(origin).dot(normal) / denominator;
        if (!Double.isFinite(distance) || distance < 0 || distance > maxDistance) return null;
        Vector3d point = new Vector3d(ray).mul(distance).add(origin);
        if (!finite(point)) return null;
        Vector3d local = new Vector3d(point).sub(plane.center);
        if (!finite(local)) return null;
        double x = local.dot(right), y = local.dot(up);
        if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
        double tolerance = Math.max(1, Math.max(plane.halfWidth, plane.halfHeight)) * EPSILON;
        if (Math.abs(x) > plane.halfWidth + tolerance || Math.abs(y) > plane.halfHeight + tolerance) return null;
        return new Hit(plane, distance, point, Math.clamp(.5 + x / (2 * plane.halfWidth), 0, 1),
                Math.clamp(.5 - y / (2 * plane.halfHeight), 0, 1));
    }

    /** Equal-distance rectangles keep their input order, allowing the caller to choose UI priority. */
    public static Hit nearest(Vector3dc origin, Vector3dc direction, Iterable<Plane> planes, double maxDistance) {
        if (planes == null) return null;
        Hit nearest = null;
        for (Plane plane : planes) {
            Hit next = intersect(origin, direction, plane, nearest == null ? maxDistance : nearest.distance);
            if (next != null && (nearest == null || next.distance < nearest.distance)) nearest = next;
        }
        return nearest;
    }

    private static boolean normalize(Vector3d value) {
        double length = value.lengthSquared();
        if (!Double.isFinite(length) || length < EPSILON * EPSILON) return false;
        value.div(Math.sqrt(length));
        return true;
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
    private static boolean finite(Vector3dc value) {
        return value != null && Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }
}
