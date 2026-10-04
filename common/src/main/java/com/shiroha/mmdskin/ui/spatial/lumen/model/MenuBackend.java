package com.shiroha.mmdskin.ui.spatial.lumen.model;

import java.util.List;

/** Integration boundary: no LWJGL, Minecraft, Vivecraft, JNI or account credentials in the UI. */
public interface MenuBackend extends AutoCloseable {
    record Entry(String id, String name, String description, boolean selected) {}
    record TextSpan(String text, int color, boolean bold) {}
    record ServerDetails(String state, String motd, String population, String version, long ping, List<String> players, List<TextSpan> description) {
        public boolean loading(){return state.equals("INITIAL")||state.equals("PINGING");}
        public boolean reachable(){return state.equals("SUCCESSFUL")||state.equals("INCOMPATIBLE");}
        public String connectionLabel(){return loading()?"检测中…":state.equals("INCOMPATIBLE")?"版本不兼容":reachable()?ping+" ms":"无法连接";}
    }
    /** Immutable-by-contract top-to-bottom RGBA8 pixels; the content key changes whenever pixels change. */
    record ImageData(String key, int width, int height, byte[] rgba) {
        public ImageData {
            if (key == null || width <= 0 || height <= 0 || width > 2048 || height > 2048
                    || rgba == null || rgba.length != width * height * 4) throw new IllegalArgumentException("Invalid menu image");
        }
    }
    List<Setting> settings();
    default boolean enabled(String id) { return true; }
    default String display(String id) { return String.valueOf(get(id)); }
    default void flush() {}
    default void refresh() {}
    default List<Entry> actions() { return List.of(); }
    default List<Entry> morphs() { return List.of(); }
    default List<Entry> stages() { return List.of(); }
    default List<Entry> materials() { return List.of(); }
    default String accountName() { return ""; }
    default String accountKindDescription() { return "账户会话"; }
    default ImageData accountFace() { return null; }
    default ImageData avatarImage(String avatarId) { return null; }
    default ServerDetails serverDetails(String worldId) { return null; }
    default boolean inWorld() { return false; }
    default boolean worldBusy(String id) { return false; }
    default boolean canDeleteWorld(String id) { return !id.equals(currentWorldId()); }
    default String serverValidationError(String name, String address) {
        return name == null || name.isBlank() || address == null || address.isBlank() ? "请填写服务器名称和地址" : "";
    }
    default ImageData worldImage(String worldId) { return null; }
    default boolean worldsLoading() { return false; }
    default String worldsLoadError() { return ""; }
    default String worldsDirectory() { return ""; }
    default String currentWorld() { return ""; }
    default String currentWorldId() { return ""; }
    default String mirrorMode() { return "OFF"; }
    default String equippedAvatar() { return ""; }
    default boolean favorite(String id) { return false; }
    default void toggleFavorite(String id) {}
    Object get(String id);
    void set(String id, Object value);
    void resetCategory(String category);
    boolean bool(String id);
    double number(String id);
    String string(String id);
    /** Selects the model that model.* preferences read and write; global preferences stay shared. */
    void selectModelContext(String avatarId);
    String modelContext();
    List<WorldEntry> worlds();
    List<AvatarEntry> avatars();
    void dispatch(MenuCommand command);
    List<MenuCommand> history();
    String status();
    @Override void close();
}
