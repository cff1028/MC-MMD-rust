package com.shiroha.mmdskin.compat.vr.mirror;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Three physical buttons beside the mirror, sharing its rigid grab transform. */
public final class VrMirrorControls {
    public static final double BUTTON_WIDTH = .55;
    public static final double BUTTON_HEIGHT = .19;
    public static final double SURFACE_OFFSET = .004;
    public static final double LABEL_OFFSET = .008;
    static final double BUTTON_X = VrMirrorGeometry.WIDTH / 2.0 + .08 + BUTTON_WIDTH / 2;
    static final double BUTTON_TOP_Y = .32;
    static final double BUTTON_SPACING = .24;
    private static ByteBufferBuilder buffer;
    private static MultiBufferSource.BufferSource text;

    private VrMirrorControls() {}

    public static VrMirrorController.Mode hit(VrMirrorGeometry mirror, Vec3 origin, Vec3 direction, double reach) {
        Vec3 point = mirror.rayHitLocal(origin, direction, reach);
        if (point == null || Math.abs(point.x - BUTTON_X) > BUTTON_WIDTH / 2) return null;
        for (VrMirrorController.Mode mode : VrMirrorController.Mode.values()) {
            if (Math.abs(point.y - buttonY(mode)) <= BUTTON_HEIGHT / 2) return mode;
        }
        return null;
    }

    public static Vec3 buttonCenter(VrMirrorGeometry mirror, VrMirrorController.Mode mode) {
        return mirror.point(BUTTON_X, buttonY(mode));
    }

    private static double buttonY(VrMirrorController.Mode mode) {
        return BUTTON_TOP_Y - mode.ordinal() * BUTTON_SPACING;
    }

    public static void renderControls(VrMirrorGeometry mirror, Camera camera, Matrix4f view) {
        renderControls(mirror, camera.getPosition(), view);
    }

    /** Called in the world pass after the mirror surface, inside the renderer's state scope. */
    public static void renderControls(VrMirrorGeometry mirror, Vec3 camera, Matrix4f view) {
        if (mirror == null || mirror.normal().dot(camera.subtract(mirror.center())) <= 0) return;
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Vec3 offset = mirror.normal().scale(SURFACE_OFFSET).subtract(camera);
        for (VrMirrorController.Mode mode : VrMirrorController.Mode.values()) {
            int color = VrMirrorController.isControlHovered(mode) ? 0xFF3D7891
                    : mode == VrMirrorController.mode() ? 0xFF2D705B : 0xFF253645;
            double y = buttonY(mode);
            vertex(builder, view, mirror.point(BUTTON_X - BUTTON_WIDTH / 2, y - BUTTON_HEIGHT / 2).add(offset), color);
            vertex(builder, view, mirror.point(BUTTON_X + BUTTON_WIDTH / 2, y - BUTTON_HEIGHT / 2).add(offset), color);
            vertex(builder, view, mirror.point(BUTTON_X + BUTTON_WIDTH / 2, y + BUTTON_HEIGHT / 2).add(offset), color);
            vertex(builder, view, mirror.point(BUTTON_X - BUTTON_WIDTH / 2, y + BUTTON_HEIGHT / 2).add(offset), color);
        }
        BufferUploader.drawWithShader(builder.buildOrThrow());
        if (buffer == null) {
            buffer = new ByteBufferBuilder(4096);
            text = MultiBufferSource.immediate(buffer);
        }
        Font font = Minecraft.getInstance().font;
        for (VrMirrorController.Mode mode : VrMirrorController.Mode.values()) {
            Vec3 center = buttonCenter(mirror, mode).add(mirror.normal().scale(LABEL_OFFSET)).subtract(camera);
            Matrix4f matrix = new Matrix4f(view).translate((float) center.x, (float) center.y, (float) center.z)
                    .rotate(mirror.rotation()).scale(.005f, -.005f, .005f);
            Component label = Component.translatableWithFallback("gui.mmdskin.vr_mirror.mode." + mode.name().toLowerCase(java.util.Locale.ROOT), mode.name());
            font.drawInBatch(label, -font.width(label) / 2.0f, -4, 0xFFFFFFFF, false,
                    matrix, text, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        }
        text.endBatch();
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix, Vec3 point, int color) {
        buffer.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z).setColor(color);
    }

    public static void release() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
            text = null;
        }
    }
}
