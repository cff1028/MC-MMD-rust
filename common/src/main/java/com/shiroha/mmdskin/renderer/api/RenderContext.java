package com.shiroha.mmdskin.renderer.api;

/**
 * 渲染上下文。
 */
public class RenderContext {

    public enum SceneType {

        WORLD,

        INVENTORY,

        ITEM,

        GUI,

        OTHER
    }

    private final SceneType sceneType;
    private final boolean isFirstPerson;
    private final boolean isMirror;
    private final boolean poseOnly;

    private RenderContext(Builder builder) {
        this.sceneType = builder.sceneType;
        this.isFirstPerson = builder.isFirstPerson;
        this.isMirror = builder.isMirror;
        this.poseOnly = builder.poseOnly;
    }

    public SceneType getSceneType() {
        return sceneType;
    }

    public boolean isFirstPerson() {
        return isFirstPerson;
    }

    public boolean isPoseOnly() { return poseOnly; }

    public boolean isMirror() {
        return isMirror;
    }

    public boolean isInventoryScene() {
        return sceneType == SceneType.INVENTORY;
    }

    public boolean isWorldScene() {
        return sceneType == SceneType.WORLD;
    }

    public static final RenderContext VR_POSE_ONLY = new Builder().poseOnly(true).build();

    public static final RenderContext WORLD = new Builder()
            .sceneType(SceneType.WORLD)
            .build();

    /** Reuse the current animated pose without advancing animation or physics. */
    public static final RenderContext MIRROR = new Builder()
            .sceneType(SceneType.WORLD)
            .mirror(true)
            .build();

    public static final RenderContext INVENTORY = new Builder()
            .sceneType(SceneType.INVENTORY)
            .mirror(true)
            .build();

    public static final RenderContext FIRST_PERSON = new Builder()
            .sceneType(SceneType.WORLD)
            .firstPerson(true)
            .build();

    public static final RenderContext ITEM = new Builder()
            .sceneType(SceneType.ITEM)
            .build();

    public static class Builder {
        private SceneType sceneType = SceneType.WORLD;
        private boolean isFirstPerson = false;
        private boolean isMirror = false;
        private boolean poseOnly;
        public Builder poseOnly(boolean value) { poseOnly = value; return this; }

        public Builder sceneType(SceneType sceneType) {
            this.sceneType = sceneType;
            return this;
        }

        public Builder firstPerson(boolean isFirstPerson) {
            this.isFirstPerson = isFirstPerson;
            return this;
        }

        public Builder mirror(boolean isMirror) {
            this.isMirror = isMirror;
            return this;
        }

        public RenderContext build() {
            return new RenderContext(this);
        }
    }
}
