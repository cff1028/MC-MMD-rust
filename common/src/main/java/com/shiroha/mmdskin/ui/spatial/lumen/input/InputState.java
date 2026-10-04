package com.shiroha.mmdskin.ui.spatial.lumen.input;

/** Logical panel coordinates and transient pointer edges, independent of GLFW/VR input. */
public final class InputState {
    public float x = -10000;
    public float y = -10000;
    public float scroll;
    public boolean down;
    public boolean pressed;
    public boolean released;

    public void endFrame() {
        pressed = false;
        released = false;
        scroll = 0;
    }

    public boolean inside(float left, float top, float width, float height) {
        return x >= left && y >= top && x < left + width && y < top + height;
    }
}
