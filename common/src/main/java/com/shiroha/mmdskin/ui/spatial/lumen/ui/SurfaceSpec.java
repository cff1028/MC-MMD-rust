package com.shiroha.mmdskin.ui.spatial.lumen.ui;

/** Placement hints for a future VR host; distances are recommendations, not a tracked pose. */
public record SurfaceSpec(Anchor anchor, float x, float y, float width, float height,
                          float recommendedWidthMetres, boolean blocksWorldActions) {
    public enum Anchor { RIGHT_HAND, HEAD_STABLE }
    public static SurfaceSpec wrist() {return new SurfaceSpec(Anchor.RIGHT_HAND,220,216,448,506,.42f,true);}
    // Includes the detached navigation dock and right-side close/hand targets.
    public static SurfaceSpec detail() {return new SurfaceSpec(Anchor.HEAD_STABLE,180,112,1144,744,1.25f,true);}
}
