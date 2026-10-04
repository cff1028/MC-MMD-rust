package com.shiroha.mmdskin.renderer.runtime.texture;

import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** Texture uploads that do not inherit pixel offsets left by GUI image uploads. */
public final class MMDTextureUpload {
    private MMDTextureUpload() {
    }

    public static int createWhiteLightMap() {
        int binding = GL46C.glGetInteger(GL46C.GL_TEXTURE_BINDING_2D);
        int unpackBuffer = GL46C.glGetInteger(GL46C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] parameters = {
            GL46C.GL_UNPACK_ALIGNMENT, GL46C.GL_UNPACK_ROW_LENGTH,
            GL46C.GL_UNPACK_SKIP_PIXELS, GL46C.GL_UNPACK_SKIP_ROWS,
            GL46C.GL_UNPACK_IMAGE_HEIGHT, GL46C.GL_UNPACK_SKIP_IMAGES
        };
        int[] previous = new int[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            previous[i] = GL46C.glGetInteger(parameters[i]);
        }

        ByteBuffer pixels = MemoryUtil.memAlloc(16 * 16 * 4);
        int texture = 0;
        boolean uploaded = false;
        try {
            while (pixels.hasRemaining()) {
                pixels.putInt(-1);
            }
            pixels.flip();
            // NativeImage can leave skip rows/pixels behind. A bound PBO would
            // also reinterpret our client-memory pointer as a buffer offset.
            GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int parameter : parameters) {
                GL46C.glPixelStorei(parameter, parameter == GL46C.GL_UNPACK_ALIGNMENT ? 4 : 0);
            }
            texture = GL46C.glGenTextures();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texture);
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA8, 16, 16, 0,
                GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, pixels);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAX_LEVEL, 0);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_LINEAR);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
            uploaded = true;
            return texture;
        } finally {
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, binding);
            GL46C.glBindBuffer(GL46C.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            for (int i = 0; i < parameters.length; i++) {
                GL46C.glPixelStorei(parameters[i], previous[i]);
            }
            if (!uploaded && texture != 0) {
                GL46C.glDeleteTextures(texture);
            }
            MemoryUtil.memFree(pixels);
        }
    }
}
