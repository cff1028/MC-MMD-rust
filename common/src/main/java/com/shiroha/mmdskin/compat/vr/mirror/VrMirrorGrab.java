package com.shiroha.mmdskin.compat.vr.mirror;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Controller-relative grab anchor; dragging starts without snapping the mirror. */
public record VrMirrorGrab(int hand, Vec3 localCenter, Quaternionf localRotation) {
    public static VrMirrorGrab begin(int hand, VrMirrorGeometry mirror, Vec3 position, Quaternionf rotation) {
        Quaternionf inverse = new Quaternionf(rotation).invert();
        return new VrMirrorGrab(hand,
                VrMirrorGeometry.rotate(mirror.center().subtract(position), inverse),
                new Quaternionf(inverse).mul(mirror.rotation()));
    }

    public VrMirrorGeometry move(Vec3 position, Quaternionf rotation) {
        return new VrMirrorGeometry(position.add(VrMirrorGeometry.rotate(localCenter, rotation)),
                new Quaternionf(rotation).mul(localRotation));
    }
}
