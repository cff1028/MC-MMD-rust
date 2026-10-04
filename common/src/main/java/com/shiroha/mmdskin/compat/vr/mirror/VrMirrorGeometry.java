package com.shiroha.mmdskin.compat.vr.mirror;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A finite world-space mirror and its off-axis reflected camera. */
public final class VrMirrorGeometry {
    public static final float WIDTH = 1.3f;
    public static final float HEIGHT = 2.2f;
    /** Visible reflection sits in front of the frame's logical/grab plane. */
    public static final double SURFACE_OFFSET = .002;
    private final Vec3 center;
    private final Quaternionf rotation;

    public VrMirrorGeometry(Vec3 center, Quaternionf rotation) {
        this.center = center;
        this.rotation = new Quaternionf(rotation).normalize();
    }

    public Vec3 center() { return center; }
    public Quaternionf rotation() { return new Quaternionf(rotation); }
    public Vec3 right() { return rotate(new Vec3(1, 0, 0), rotation); }
    public Vec3 up() { return rotate(new Vec3(0, 1, 0), rotation); }
    public Vec3 normal() { return rotate(new Vec3(0, 0, 1), rotation); }

    public Vec3 point(double x, double y) {
        return center.add(right().scale(x)).add(up().scale(y));
    }

    /** Conservative bounds include the frame and its side controls. */
    public AABB bounds() {
        Vec3 right = right().scale(WIDTH / 2);
        Vec3 up = up().scale(HEIGHT / 2);
        Vec3 extent = new Vec3(Math.abs(right.x) + Math.abs(up.x),
                Math.abs(right.y) + Math.abs(up.y), Math.abs(right.z) + Math.abs(up.z));
        return new AABB(center.subtract(extent), center.add(extent))
                .inflate(.08 + VrMirrorControls.BUTTON_WIDTH + .03);
    }

    public boolean isVisibleFrom(Vec3 eye, Matrix4f view, Matrix4f projection) {
        if (eye.distanceToSqr(center) > 48 * 48) return false;
        Frustum frustum = new Frustum(view, projection);
        frustum.prepare(eye.x, eye.y, eye.z);
        return frustum.isVisible(bounds());
    }

    /** Returns ray distance, or -1 for back faces, parallel rays and misses. */
    public double hitDistance(Vec3 origin, Vec3 direction, double reach) {
        Vec3 hit = rayHitLocal(origin, direction, reach);
        return hit != null && Math.abs(hit.x) <= WIDTH / 2.0
                && Math.abs(hit.y) <= HEIGHT / 2.0 ? hit.z : -1;
    }

    /** Local plane coordinates in x/y, and ray distance in z; no rectangle clipping. */
    public Vec3 rayHitLocal(Vec3 origin, Vec3 direction, double reach) {
        Vec3 n = normal();
        double denominator = direction.dot(n);
        if (!Double.isFinite(denominator) || denominator >= -1.0e-6) return null;
        double distance = center.subtract(origin).dot(n) / denominator;
        if (!Double.isFinite(distance) || distance < 0 || distance > reach) return null;
        Vec3 local = origin.add(direction.scale(distance)).subtract(center);
        return new Vec3(local.dot(right()), local.dot(up()), distance);
    }

    public ReflectedView reflectedView(Vec3 viewer) {
        return reflectedView(viewer, 64);
    }

    public ReflectedView reflectedView(Vec3 viewer, float farDistance) {
        Vec3 n = normal();
        double distance = viewer.subtract(center).dot(n);
        if (distance <= 0.035 || distance > 48) return null;
        Vec3 eye = viewer.subtract(n.scale(distance * 2));
        Vec3 cameraRight = right().scale(-1);
        Vec3 u = up();
        Vec3 toPlane = center.subtract(eye);
        float near = (float) Math.max(0.02, distance);
        float factor = near / (float) distance;
        float cx = (float) toPlane.dot(cameraRight);
        float cy = (float) toPlane.dot(u);
        Matrix4f projection = new Matrix4f().frustum((cx - WIDTH / 2) * factor,
                (cx + WIDTH / 2) * factor, (cy - HEIGHT / 2) * factor,
                (cy + HEIGHT / 2) * factor, near,
                near + (Float.isFinite(farDistance) ? Math.max(64, farDistance) : 64));
        Matrix4f viewRotation = new Matrix4f().lookAt(0, 0, 0,
                (float) n.x, (float) n.y, (float) n.z, (float) u.x, (float) u.y, (float) u.z);
        return new ReflectedView(eye, viewRotation, projection);
    }

    public static Vec3 rotate(Vec3 point, Quaternionf quaternion) {
        Vector3f v = quaternion.transform(new Vector3f((float) point.x, (float) point.y, (float) point.z));
        return new Vec3(v.x, v.y, v.z);
    }

    public record ReflectedView(Vec3 eye, Matrix4f rotation, Matrix4f projection) {}
}
