package com.shiroha.mmdskin.compat.vr.pointer;

import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorController;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorControls;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorGeometry;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Visible mirror UI surfaces, picked in world space with normal world occlusion. */
public final class VrPointerMirrorSurfaces {
    private VrPointerMirrorSurfaces() {}

    /** Read immediately before picking: grabbing can replace the geometry between render stages. */
    public static List<VrPointerGeometry.Plane> current() {
        return VrMirrorController.isEnabled() ? from(VrMirrorController.geometry()) : List.of();
    }

    /**
     * Snapshots the same transform and finite extents used by the mirror renderer.
     * The planes are double-sided; this does not change mirror grab/click input behavior.
     * Treat hits as UI for visibility, but keep depth testing and world-distance clipping.
     */
    public static List<VrPointerGeometry.Plane> from(VrMirrorGeometry mirror) {
        if (mirror == null) return List.of();
        Vector3d right = vector(mirror.right()), up = vector(mirror.up());
        Vec3 normal = mirror.normal();
        List<VrPointerGeometry.Plane> surfaces = new ArrayList<>(4);
        surfaces.add(new VrPointerGeometry.Plane("mirror",
                vector(mirror.center().add(normal.scale(VrMirrorGeometry.SURFACE_OFFSET))),
                right, up, VrMirrorGeometry.WIDTH / 2.0, VrMirrorGeometry.HEIGHT / 2.0));
        for (VrMirrorController.Mode mode : VrMirrorController.Mode.values()) {
            // The label is in front of the button background. A dot on the background plane
            // would disappear under its text even though the hit rectangle is correct.
            Vec3 center = VrMirrorControls.buttonCenter(mirror, mode)
                    .add(normal.scale(VrMirrorControls.LABEL_OFFSET));
            surfaces.add(new VrPointerGeometry.Plane("mirror-control-" + mode.name().toLowerCase(Locale.ROOT),
                    vector(center), right, up, VrMirrorControls.BUTTON_WIDTH / 2,
                    VrMirrorControls.BUTTON_HEIGHT / 2));
        }
        return List.copyOf(surfaces);
    }

    private static Vector3d vector(Vec3 point) { return new Vector3d(point.x, point.y, point.z); }
}
