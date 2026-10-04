package com.shiroha.mmdskin.ui.spatial;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Physical room metres; UI pixels never participate in VR world scaling. */
public final class SpatialMenuGeometry {
    private SpatialMenuGeometry() {}
    public record Hit(float u, float v, float distance, boolean inside) {}
    public static Hit intersect(Matrix4fc panel, float width, float height, Matrix4fc aim) {
        if (panel == null || aim == null || !panel.isFinite() || !aim.isFinite()
                || !Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) return null;
        Matrix4f inverse = new Matrix4f(panel).invert();
        Vector3f origin = inverse.transformPosition(aim.getTranslation(new Vector3f()));
        Vector3f ray = aim.transformDirection(0, 0, -1, new Vector3f()).normalize();
        inverse.transformDirection(ray);
        if (ray.z >= -1e-5f) return null; // never hit the back face or a parallel plane
        float distance = -origin.z / ray.z;
        if (!Float.isFinite(distance) || distance <= 0 || distance > 8) return null;
        float u = .5f + (origin.x + ray.x * distance) / width;
        float v = .5f - (origin.y + ray.y * distance) / height;
        return new Hit(u, v, distance, u >= 0 && u < 1 && v >= 0 && v < 1);
    }
    public static Matrix4f faceHead(Vector3fc center, Vector3fc head) {
        Vector3f normal = new Vector3f(head).sub(center);
        if (normal.lengthSquared() < 1e-6f) normal.set(0, 0, 1);
        normal.normalize();
        float yaw = (float)Math.atan2(normal.x, normal.z);
        float pitch = (float)-Math.asin(Math.clamp(normal.y, -.95f, .95f));
        return new Matrix4f().translation(center).rotateY(yaw).rotateX(pitch);
    }
}
