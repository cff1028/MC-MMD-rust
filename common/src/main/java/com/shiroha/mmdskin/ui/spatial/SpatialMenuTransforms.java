package com.shiroha.mmdskin.ui.spatial;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Pure room-space geometry shared by rendering and finite menu-ray picking. */
public final class SpatialMenuTransforms {
    private SpatialMenuTransforms() {}

    public enum DetailBehavior {
        FIXED_CLOSE("不跟随，超距关闭"), FIXED_STAY("不跟随，不自动关闭"),
        FOLLOW_POSITION("跟随玩家，保持距离"), FOLLOW_VIEW("跟随玩家和视角");
        private final String label;
        DetailBehavior(String label) { this.label = label; }
        public String label() { return label; }
        public boolean follows() { return this == FOLLOW_POSITION || this == FOLLOW_VIEW; }
        public static DetailBehavior parse(String value) {
            try { return valueOf(value); } catch (IllegalArgumentException | NullPointerException ignored) { return FIXED_CLOSE; }
        }
    }

    /** Keep world anchors in doubles, including at Minecraft's world border. */
    public record WorldFrame(double x, double y, double z, float rotation, float scale) {
        public boolean valid() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Float.isFinite(rotation) && Float.isFinite(scale) && scale > 0;
        }
        public Vector3d toWorld(Vector3fc room) {
            double c = Math.cos(rotation), s = Math.sin(rotation);
            return new Vector3d(x + scale * (c * room.x() + s * room.z()), y + scale * room.y(),
                    z + scale * (-s * room.x() + c * room.z()));
        }
        public Vector3f toRoom(Vector3dc world) {
            double dx = (world.x() - x) / scale, dy = (world.y() - y) / scale, dz = (world.z() - z) / scale;
            double c = Math.cos(rotation), s = Math.sin(rotation);
            return new Vector3f((float)(c * dx - s * dz), (float)dy, (float)(s * dx + c * dz));
        }
    }

    /** Detailed panels are world-anchored; the rendering/picking room frame can change independently. */
    public static final class DetailMotion {
        private final Vector3d center = new Vector3d(), target = new Vector3d(), anchorHead = new Vector3d(), lastHead = new Vector3d();
        private final Vector3f direction = new Vector3f(0, 0, -1);
        private DetailBehavior behavior;
        private double distance;
        private float scale, yaw, pitch, targetYaw, targetPitch;
        private boolean initialized;

        /** False means tracking is invalid or the fixed panel exceeded its explicit closing distance. */
        public boolean update(WorldFrame world, Vector3fc roomHead, Vector3fc roomDirection,
                DetailBehavior nextBehavior, double nextDistance, double closeDistance,
                double positionMs, double rotationMs, double seconds) {
            if (world == null || !world.valid() || roomHead == null || !roomHead.isFinite()
                    || roomDirection == null || !roomDirection.isFinite() || roomDirection.lengthSquared() < 1e-8f) {
                reset(); return false;
            }
            if (nextBehavior == null) nextBehavior = DetailBehavior.FIXED_CLOSE;
            nextDistance = finiteClamp(nextDistance, .6, 2.5, 1.35);
            Vector3d head = world.toWorld(roomHead);
            boolean first = !initialized, changedBehavior = behavior != nextBehavior;
            boolean changedDistance = Double.compare(distance, nextDistance) != 0;
            boolean changedScale = Float.compare(scale, world.scale()) != 0;
            boolean teleported = initialized && head.distance(lastHead) / world.scale() > Math.max(2.5, nextDistance * 2);
            if (first || changedBehavior) {
                direction.set(placementDirection(roomDirection, world.rotation(), direction));
                anchorHead.set(head);
            } else if (changedDistance || changedScale) {
                // Moving the distance slider moves along the current viewing line, without a sideways jump.
                if (!nextBehavior.follows() && target.distanceSquared(head) > 1e-8)
                    direction.set((float)(target.x - head.x), (float)(target.y - head.y), (float)(target.z - head.z)).normalize();
                anchorHead.set(head);
            }
            behavior = nextBehavior; distance = nextDistance; scale = world.scale();
            if (behavior == DetailBehavior.FOLLOW_VIEW) direction.set(placementDirection(roomDirection, world.rotation(), direction));
            if (first || changedBehavior || changedDistance || changedScale || behavior.follows()) {
                Vector3d base = behavior.follows() ? head : anchorHead;
                target.set(base).add(direction.x * distance * scale, direction.y * distance * scale, direction.z * distance * scale);
                targetYaw = (float)Math.atan2(-direction.x, -direction.z);
                targetPitch = (float)Math.asin(Math.clamp(direction.y, -.98f, .98f));
            }
            if (behavior == DetailBehavior.FIXED_CLOSE && !first && !changedBehavior && !changedDistance && !changedScale
                    && head.distance(target) / scale > minimumCloseDistance(distance, closeDistance)) return false;
            boolean snap = first || teleported && behavior.follows();
            if (snap) { center.set(target); yaw = targetYaw; pitch = targetPitch; }
            else {
                center.lerp(target, smoothingAlpha(seconds, positionMs));
                float rotationAlpha = (float)smoothingAlpha(seconds, rotationMs);
                yaw += shortestAngle(targetYaw - yaw) * rotationAlpha;
                pitch += (targetPitch - pitch) * rotationAlpha;
            }
            lastHead.set(head); initialized = true;
            return true;
        }

        public Matrix4f roomPose(WorldFrame world) {
            if (!initialized || world == null || !world.valid()) return null;
            return new Matrix4f().translation(world.toRoom(center)).rotateY(yaw - world.rotation()).rotateX(pitch);
        }

        /** Builds a separate destination without advancing the interactive panel's motion. */
        public boolean previewDistanceFrom(DetailMotion source, WorldFrame world, Vector3fc roomHead,
                Vector3fc roomDirection, double nextDistance) {
            if (!source.initialized || source == this) { reset(); return false; }
            center.set(source.center); yaw = source.yaw; pitch = source.pitch;
            // Aim along the visible panel, even if a previous movement was still easing.
            target.set(source.center); anchorHead.set(source.anchorHead); lastHead.set(source.lastHead);
            direction.set(source.direction); behavior = source.behavior; scale = source.scale;
            distance = Double.NaN; initialized = true;
            return update(world, roomHead, roomDirection, behavior, nextDistance, 32, 0, 0, 0);
        }

        /** Commit only the destination; keep the displayed pose so existing easing starts there. */
        public void acceptDistancePreview(DetailMotion preview) {
            if (!initialized || !preview.initialized || preview == this) return;
            target.set(preview.center); anchorHead.set(preview.anchorHead); lastHead.set(preview.lastHead);
            direction.set(preview.direction); behavior = preview.behavior;
            distance = preview.distance; scale = preview.scale;
            targetYaw = preview.yaw; targetPitch = preview.pitch;
        }
        public void reset() { initialized = false; behavior = null; }
        private static Vector3f placementDirection(Vector3fc room, float rotation, Vector3fc fallback) {
            Vector3f look = new Vector3f(room).normalize().rotateY(rotation);
            float horizontal = (float)Math.sqrt(look.x * look.x + look.z * look.z);
            float heading = horizontal > .02f ? (float)Math.atan2(look.x, look.z)
                    : (float)Math.atan2(fallback.x(), fallback.z());
            float elevation = (float)Math.clamp(Math.asin(Math.clamp(look.y, -1, 1)) - .085, -1.22, 1.22);
            float h = (float)Math.cos(elevation);
            return new Vector3f((float)Math.sin(heading) * h, (float)Math.sin(elevation), (float)Math.cos(heading) * h);
        }
    }

    public static double minimumCloseDistance(double openingDistance, double requested) {
        double minimum = finiteClamp(openingDistance, .6, 2.5, 1.35) * 2 + .1;
        return finiteClamp(requested, minimum, 32, Math.max(4, minimum));
    }
    public static double smoothingAlpha(double seconds, double milliseconds) {
        if (!Double.isFinite(milliseconds) || milliseconds <= 0) return 1;
        if (!Double.isFinite(seconds) || seconds <= 0) return 0;
        return -Math.expm1(-seconds * 1000 / milliseconds);
    }
    private static double finiteClamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
    private static float shortestAngle(float angle) { return (float)Math.atan2(Math.sin(angle), Math.cos(angle)); }

    public static Matrix4f modelView(Matrix4fc roomPanel, float width, float height,
            Vector3fc originRelativeToEye, float worldRotation, float worldScale, Matrix4fc eyeView) {
        if (roomPanel == null || !roomPanel.isFinite() || eyeView == null || !eyeView.isFinite()
                || originRelativeToEye == null || !originRelativeToEye.isFinite()
                || !Float.isFinite(worldRotation) || !Float.isFinite(worldScale) || worldScale <= 0
                || !Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) return null;
        return new Matrix4f(eyeView).translate(originRelativeToEye).rotateY(worldRotation)
                .scale(worldScale).mul(roomPanel).scale(width, height, 1);
    }

    /** Opening edge must return to neutral before another forward gesture can open the menu. */
    public static final class ForwardGesture {
        private boolean armed;
        public boolean update(boolean tracked, float x, float y) {
            if (!tracked || !Float.isFinite(x) || !Float.isFinite(y)) { armed = false; return false; }
            if (Math.abs(x) < .35f && Math.abs(y) < .35f) armed = true;
            if (armed && y >= .72f && y > Math.abs(x)) { armed = false; return true; }
            return false;
        }
        public void reset() { armed = false; }
    }
}
