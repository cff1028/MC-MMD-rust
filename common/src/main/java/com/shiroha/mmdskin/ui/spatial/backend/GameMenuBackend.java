package com.shiroha.mmdskin.ui.spatial.backend;

import com.shiroha.mmdskin.asset.catalog.ModelInfo;
import com.shiroha.mmdskin.asset.catalog.AnimationInfo;
import com.shiroha.mmdskin.asset.catalog.MorphInfo;
import com.shiroha.mmdskin.expression.BuiltinExpressionRegistry;
import com.shiroha.mmdskin.expression.ExpressionSelection;
import com.shiroha.mmdskin.expression.ExpressionSelectionCodec;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorController;
import com.shiroha.mmdskin.config.*;
import com.shiroha.mmdskin.player.runtime.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import com.shiroha.mmdskin.ui.config.*;
import com.shiroha.mmdskin.ui.network.ActionWheelNetworkHandler;
import com.shiroha.mmdskin.ui.selector.*;
import com.shiroha.mmdskin.ui.selector.application.MaterialVisibilityApplicationService;
import com.shiroha.mmdskin.ui.spatial.SpatialMenuHost;
import com.shiroha.mmdskin.ui.spatial.lumen.model.*;
import com.shiroha.mmdskin.ui.stage.StageSelectScreen;
import com.shiroha.mmdskin.ui.stage.StageWorkbenchFacade;
import com.shiroha.mmdskin.ui.wheel.service.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.options.*;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.*;
import java.util.function.Supplier;

/** Client-thread adapter for the portable Lumen UI. All visible values/actions are backed by game state. */
public final class GameMenuBackend implements MenuBackend {
    private static final Logger LOGGER = LogManager.getLogger();
    private final Minecraft mc = Minecraft.getInstance();
    private final Runnable closeMenu;
    private final Map<String, SettingBinding> bindings = new LinkedHashMap<>();
    private final List<MenuCommand> history = new ArrayList<>();
    private final MinecraftSettingsAdapter vanilla = new MinecraftSettingsAdapter();
    private MmdSettingsAdapter mmd;
    private VivecraftSettingsAdapter vive;
    private List<Setting> catalog = List.of();
    private List<AvatarEntry> avatars = List.of();
    private List<ActionWheelConfig.ActionEntry> motions = List.of();
    private List<MorphOption> morphs = List.of();
    private List<StagePack> stages = List.of();
    private List<ServerData> servers = List.of();
    private final AsyncWorldCatalog localWorlds = new AsyncWorldCatalog(mc.getLevelSource());
    private final MenuImageCache images = new MenuImageCache();
    private final MenuAvatarCache portraits = new MenuAvatarCache();
    private final MenuServerStatus serverStatus = new MenuServerStatus(this::refreshWorldImages);
    private long worldImageRevision = -1;
    private String serverLoadError = "";
    private ActiveWorld activeWorld;
    private record ActiveWorld(String id, String name, String description, java.nio.file.Path icon) {}
    private MaterialVisibilityApplicationService.MaterialScreenContext materialContext;
    private List<MaterialVisibilityApplicationService.MaterialEntryState> materialRows = List.of();
    private String status = "";
    private boolean closed;
    private long materialCheck;
    private final ArrayDeque<Runnable> afterFrame = new ArrayDeque<>();
    private int frameDepth;
    private boolean draining, flushQueued;
    private final Set<String> deletingWorlds = new HashSet<>();
    private final java.util.concurrent.ExecutorService worldEdits = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MMD-WorldManagement"); thread.setDaemon(true); return thread;
    });

    /** Renderers must pair these around their complete GL guard, not just NanoVG.endFrame(). */
    public void beginFrame() { if(frameDepth==0){portraits.tickSkin();serverStatus.tick();}frameDepth++; }
    public void menuClosed() { serverStatus.reset(); }
    public void endFrame() {
        if (frameDepth <= 0) throw new IllegalStateException("Unbalanced menu frame");
        if (--frameDepth != 0 || draining) return;
        draining = true;
        try {
            while (!afterFrame.isEmpty()) {
                try { afterFrame.removeFirst().run(); }
                catch (RuntimeException | LinkageError failure) { error("菜单操作未完成", failure); }
            }
        } finally { draining = false; }
    }

    public GameMenuBackend(Runnable closeMenu) {
        this.closeMenu = Objects.requireNonNull(closeMenu);
        try { mmd = new MmdSettingsAdapter(ModelSelectorConfig.getInstance().getSelectedModel()); }
        catch (ReflectiveOperationException | RuntimeException failure) { error("MMD 设置读取失败", failure); }
        try { vive = new VivecraftSettingsAdapter(); }
        catch (ClassNotFoundException ignored) { /* The menu remains useful without Vivecraft. */ }
        catch (ReflectiveOperationException | RuntimeException failure) { error("Vivecraft 设置读取失败", failure); }
        refresh();
    }

    @Override public List<Setting> settings() { return catalog; }
    @Override public Object get(String id) { return binding(id).read().get(); }
    @Override public void set(String id, Object value) {
        try {
            SettingBinding binding = binding(id);
            if (frameDepth > 0 && binding.schema().kind() == Setting.Kind.ACTION) {
                afterFrame.addLast(() -> set(id, value)); return;
            }
            if (!binding.enabled().getAsBoolean()) { status = "此项目在当前状态下不可修改"; return; }
            boolean action = binding.schema().kind() == Setting.Kind.ACTION;
            if (action) status = binding.schema().title();
            binding.write().accept(binding.schema().normalize(value));
            if (!action) status = binding.schema().title() + " · 已修改";
            if (action) flush();
        } catch (RuntimeException failure) { error("设置未应用", failure); }
    }
    @Override public boolean enabled(String id) {
        try { return binding(id).enabled().getAsBoolean(); } catch (RuntimeException ignored) { return false; }
    }
    @Override public String display(String id) {
        try { return binding(id).display().get(); } catch (RuntimeException failure) { return "不可用"; }
    }
    @Override public boolean bool(String id) { return Boolean.TRUE.equals(get(id)); }
    @Override public double number(String id) { return ((Number)get(id)).doubleValue(); }
    @Override public String string(String id) { return String.valueOf(get(id)); }
    @Override public void resetCategory(String category) {
        if (frameDepth > 0) { afterFrame.addLast(() -> resetCategory(category)); return; }
        try {
            for (SettingBinding binding : bindings.values()) {
                if (binding.schema().category().equals(category) && binding.schema().kind() != Setting.Kind.ACTION && binding.enabled().getAsBoolean()) binding.reset().run();
            }
            flush(); status = category + " · 已恢复默认设置";
        } catch (RuntimeException failure) { error("恢复默认设置失败", failure); }
    }
    @Override public void selectModelContext(String avatarId) {
        if (mmd == null || Objects.equals(mmd.modelName(), avatarId)) return;
        if (avatars.stream().noneMatch(avatar -> avatar.id().equals(avatarId))) { status = "模型已经不在资源目录中"; return; }
        mmd.selectModel(avatarId); materialContext = null; materialRows = List.of();
    }
    @Override public String modelContext() { return mmd == null ? UIConstants.DEFAULT_MODEL_NAME : mmd.modelName(); }
    @Override public List<AvatarEntry> avatars() { return avatars.stream().map(avatar -> new AvatarEntry(avatar.id(), avatar.name(), avatar.subtitle(), avatar.kind(), avatar.palette(), favorite("avatar:" + avatar.id()))).toList(); }
    @Override public List<WorldEntry> worlds() {
        pollWorldImages();
        List<WorldEntry> result = new ArrayList<>();
        if (activeWorld != null) result.add(new WorldEntry(activeWorld.id(), activeWorld.name(), activeWorld.description(), "本地", 0, favorite(activeWorld.id())));
        for (LevelSummary world : localWorlds.entries()) {
            String id = "local:" + world.getLevelId();
            if (activeWorld == null || !activeWorld.id().equals(id)) result.add(new WorldEntry(id,
                    world.getLevelName(), world.getInfo().getString(), "本地", result.size() % 6, favorite(id)));
        }
        for (int i = 0; i < servers.size(); i++) {
            ServerData server = servers.get(i);
            result.add(new WorldEntry("server:" + i, server.name, server.ip, "服务器", i % 6, favorite("server:" + i)));
        }
        return List.copyOf(result);
    }
    @Override public boolean worldsLoading() { return localWorlds.loading(); }
    @Override public String worldsLoadError() { return String.join("\n", java.util.stream.Stream.of(localWorlds.error(), serverLoadError).filter(text -> !text.isBlank()).toList()); }
    @Override public String worldsDirectory() { return mc.getLevelSource().getBaseDir().toAbsolutePath().normalize().toString(); }
    @Override public ImageData worldImage(String id) { pollWorldImages(); return images.world(id); }
    @Override public ImageData accountFace() { return images.accountFace(); }
    @Override public ImageData avatarImage(String id) { return portraits.image(id); }
    @Override public boolean inWorld() { return mc.level != null; }
    @Override public boolean worldBusy(String id) { return deletingWorlds.contains(id); }
    @Override public boolean canDeleteWorld(String id) {
        if (worldBusy(id)) return false;
        if (id.startsWith("server:")) return true;
        return !id.equals(currentWorldId()) && localWorlds.entries().stream()
                .anyMatch(world -> id.equals("local:" + world.getLevelId()) && world.canDelete());
    }
    @Override public ServerDetails serverDetails(String id) {
        if(id==null||!id.startsWith("server:"))return null;
        try{return serverStatus.details(servers.get(Integer.parseInt(id.substring(7))));}
        catch(IndexOutOfBoundsException|NumberFormatException stale){return null;}
    }
    private void pollWorldImages() {
        long revision = localWorlds.revision();
        if (revision != worldImageRevision) { worldImageRevision = revision; refreshWorldImages(); }
    }
    private void refreshWorldImages() {
        images.refreshWorlds(localWorlds.entries(), servers, activeWorld == null ? "" : activeWorld.id(), activeWorld == null ? null : activeWorld.icon());
    }
    @Override public List<Entry> actions() { return motions.stream().map(action -> new Entry(action.animId, action.name, action.source == null ? "" : action.source, false)).toList(); }
    @Override public List<Entry> morphs() { return morphs.stream().filter(morph -> !morph.resetAction()).map(morph -> new Entry(morph.syncToken(), morph.displayName(), "模型表情", false)).toList(); }
    @Override public List<Entry> stages() { return stages.stream().map(stage -> new Entry(stage.getName(), stage.getName(), stage.getVmdFiles().size() + " 个动作 / 相机文件", false)).toList(); }
    @Override public List<Entry> materials() {
        if (!modelContext().equals(equippedAvatar()) || mc.player == null) return List.of();
        if (System.nanoTime() - materialCheck > 1_000_000_000L) {
            materialCheck = System.nanoTime();
            var next = ModelSelectorServices.materialVisibility().createPlayerContext().orElse(null);
            if (!Objects.equals(materialContext, next)) {
                materialContext = next;
                materialRows = next == null ? List.of() : ModelSelectorServices.materialVisibility().loadMaterials(next);
            }
        }
        return materialRows.stream().map(row -> new Entry(Integer.toString(row.index()), row.name(), "", row.visible())).toList();
    }
    @Override public String accountName() { return mc.getUser().getName(); }
    @Override public String accountKindDescription() {
        var user = mc.getUser();
        // Session metadata is a description, not proof of account ownership or a successful server authentication.
        String token = user.getAccessToken();
        if (token == null || token.isBlank() || token.equals("0") || token.equalsIgnoreCase("null")
                || user.getProfileId().version() == 3) return "离线会话";
        return switch (user.getType()) {
            case MSA -> "Microsoft 账户会话";
            case MOJANG -> "Mojang 账户会话";
            default -> "其他启动器会话";
        };
    }
    @Override public String currentWorld() {
        if (mc.getSingleplayerServer() != null) return mc.getSingleplayerServer().getWorldData().getLevelName();
        if (mc.getCurrentServer() != null) return mc.getCurrentServer().name;
        return "主菜单";
    }
    @Override public String currentWorldId() {
        if (mc.getSingleplayerServer() != null) {
            var path = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize();
            return "local:" + path.getFileName();
        }
        if (mc.getCurrentServer() != null) {
            for (int i = 0; i < servers.size(); i++) if (servers.get(i).ip.equals(mc.getCurrentServer().ip)) return "server:" + i;
        }
        return "";
    }
    @Override public String equippedAvatar() { return ModelSelectorConfig.getInstance().getSelectedModel(); }
    @Override public String mirrorMode() { return VrMirrorController.mode().name(); }
    @Override public boolean favorite(String id) { return mmd != null && mmd.favorite(favoriteKey(id)); }
    @Override public void toggleFavorite(String id) { if (mmd != null) { mmd.toggleFavorite(favoriteKey(id)); flush(); } }
    private String favoriteKey(String id) {
        if (id.startsWith("server:")) {
            try { return "server-address:" + servers.get(Integer.parseInt(id.substring(7))).ip; }
            catch (RuntimeException ignored) { return id; }
        }
        return id;
    }
    @Override public List<MenuCommand> history() { return List.copyOf(history); }
    @Override public String status() { return status; }

    @Override public void flush() {
        if (frameDepth > 0) {
            if (!flushQueued) {
                flushQueued = true;
                afterFrame.addLast(() -> { flushQueued = false; flush(); });
            }
            return;
        }
        flushPending();
    }
    private boolean flushPending() {
        try {
            vanilla.flush(); if (vive != null) vive.flush(); if (mmd != null) mmd.flush();
            return true;
        } catch (Exception | LinkageError failure) {
            error("部分设置保存或应用失败", failure);
            return false;
        }
    }
    @Override public void refresh() {
        if (frameDepth > 0) { afterFrame.addLast(this::refresh); return; }
        if (closed) return;
        portraits.refresh();
        refreshWorldCatalog();
        try { images.refreshAccount(); } catch (RuntimeException failure) { LOGGER.debug("[Spatial UI] Account skin is not available yet", failure); }
        // Editors publish fresh defensive copies; only replace our editing session after saving succeeds.
        if (!flushPending()) return;
        if (mmd != null) {
            try { mmd = new MmdSettingsAdapter(mmd.modelName()); }
            catch (ReflectiveOperationException | RuntimeException failure) { error("MMD 设置刷新失败", failure); return; }
        }
        try {
            var cards = ModelSelectorServices.modelSelection().loadModelCards();
            List<AvatarEntry> actual = new ArrayList<>();
            for (var card : cards) actual.add(new AvatarEntry(card.displayName(), card.displayName(), card.configurable() ? "本地 MMD 模型" : "Minecraft 玩家模型", card.configurable() ? "MMD" : "原版", actual.size() % 6, false));
            avatars = List.copyOf(actual);
            refreshModelResources();
            stages = StageWorkbenchFacade.getInstance().loadStagePacks();
        } catch (Exception failure) { error("部分本地资源读取失败", failure); }
        rebuildCatalog();
    }
    private void refreshWorldCatalog() {
        serverStatus.reset();
        // A missing model/stage resource must never prevent the native save-directory scan.
        var server = mc.getSingleplayerServer();
        if (server == null) activeWorld = null;
        else {
            var world = server.getWorldData();
            activeWorld = new ActiveWorld(currentWorldId(), world.getLevelName(), "正在游玩 · " + world.getGameType().getLongDisplayName().getString(),
                    server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ICON_FILE));
        }
        // Our integrated server already owns this directory lock. Its metadata comes from the live server above.
        localWorlds.refresh(activeWorld == null ? "" : activeWorld.id().substring("local:".length()));
        try {
            ServerList list = new ServerList(mc); list.load();
            List<ServerData> data = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) data.add(list.get(i));
            servers = List.copyOf(data); serverLoadError = "";
        } catch (RuntimeException failure) { serverLoadError = "无法读取当前实例的服务器列表。请刷新后重试。"; LOGGER.warn("[Spatial UI] Server list unavailable", failure); }
        refreshWorldImages(); worldImageRevision = localWorlds.revision();
    }
    private void refreshModelResources() {
        Map<String, ActionWheelConfig.ActionEntry> availableActions = new LinkedHashMap<>();
        for (AnimationInfo info : AnimationInfo.scanAnimationsForModel(equippedAvatar())) {
            var entry = ActionWheelConfig.ActionEntry.from(info); availableActions.putIfAbsent(entry.animId, entry);
        }
        motions = List.copyOf(availableActions.values());
        List<MorphOption> availableMorphs = new ArrayList<>();
        for (var preset : BuiltinExpressionRegistry.createConfigEntries()) availableMorphs.add(new MorphOption(
                preset.displayName, preset.morphName, null, ExpressionSelectionCodec.encode(ExpressionSelection.preset(preset.presetId)), false));
        Set<String> names = new HashSet<>();
        for (MorphInfo info : MorphInfo.scanMorphsForModel(equippedAvatar())) if (names.add(info.getMorphName())) availableMorphs.add(new MorphOption(
                info.getDisplayName(), info.getMorphName(), info.getFilePath(), ExpressionSelectionCodec.encode(ExpressionSelection.file(info.getMorphName())), false));
        availableMorphs.add(new MorphOption("重置表情", ExpressionSelectionCodec.RESET_TOKEN, null, ExpressionSelectionCodec.RESET_TOKEN, true));
        morphs = List.copyOf(availableMorphs);
    }
    private void rebuildCatalog() {
        bindings.clear();
        try { add(vanilla.bindings()); } catch (RuntimeException failure) { error("Minecraft 选项读取失败", failure); }
        if (vive != null) try { add(vive.bindings()); } catch (RuntimeException failure) { error("Vivecraft 选项读取失败", failure); }
        if (mmd != null) try { add(mmd.bindings()); } catch (RuntimeException failure) { error("MMD 选项读取失败", failure); }
        specializedSettings();
        catalog = bindings.values().stream().map(SettingBinding::schema).toList();
    }
    private void add(List<SettingBinding> rows) { for (SettingBinding row : rows) bindings.put(row.schema().id(), row); }
    private void action(String id, String category, String section, String title, String description, Runnable run) {
        Setting schema = new Setting(id, category, section, title, description, Setting.Kind.ACTION, 0, 1, 1, "", List.of(), "game:" + id);
        bindings.put(id, new SettingBinding(schema, () -> "", ignored -> run.run(), () -> "打开", () -> true, () -> {}));
    }
    private void specializedSettings() {
        action("native.minecraft", "Minecraft", "完整设置与资源", "完整 Minecraft 设置", "打开原版完整选项，包括世界难度。", () -> nativeScreen(new OptionsScreen(mc.screen, mc.options)));
        action("native.video", "Minecraft", "完整设置与资源", "画面设置与显卡选项", "包括分辨率、Sodium 等已安装 Mod 提供的画面设置。", () -> nativeScreen(new VideoSettingsScreen(mc.screen, mc, mc.options)));
        action("native.language", "Minecraft", "语言与字体", "语言列表", "切换实际游戏语言及资源翻译。", () -> nativeScreen(new LanguageSelectScreen(mc.screen, mc.options, mc.getLanguageManager())));
        action("native.controls", "Minecraft", "操作", "按键绑定与控制", "配置 Minecraft 和已安装 Mod 的全部按键。", () -> nativeScreen(new ControlsScreen(mc.screen, mc.options)));
        action("native.skin", "Minecraft", "完整设置与资源", "皮肤部件", "帽子、外套、左右衣袖与披风。", () -> nativeScreen(new SkinCustomizationScreen(mc.screen, mc.options)));
        action("native.chat", "Minecraft", "聊天", "完整聊天设置", "包含聊天预览与原版帮助。", () -> nativeScreen(new ChatOptionsScreen(mc.screen, mc.options)));
        action("native.accessibility", "Minecraft", "无障碍", "完整无障碍设置", "包含叙述人和辅助功能。", () -> nativeScreen(new AccessibilityOptionsScreen(mc.screen, mc.options)));
        action("native.sound", "Minecraft", "声音与音量", "声音设备设置", "选择当前可用的声音设备。", () -> nativeScreen(new SoundOptionsScreen(mc.screen, mc.options)));
        action("native.packs", "Minecraft", "完整设置与资源", "资源包", "选择并重新加载游戏资源包。", () -> {
            Screen parent = mc.screen;
            nativeScreen(new net.minecraft.client.gui.screens.packs.PackSelectionScreen(mc.getResourcePackRepository(), repository -> {
                mc.options.updateResourcePacks(repository); mc.options.save(); mc.setScreen(parent);
            }, mc.getResourcePackDirectory(), Component.translatable("resourcePack.title")));
        });
        action("native.worlds", "Minecraft", "完整设置与资源", "存档管理", "进入存档选择界面，提供创建、备份和编辑功能。", () -> { requireNoWorld(); nativeScreen(new SelectWorldScreen(mc.screen)); });
        action("native.servers", "Minecraft", "在线与隐私", "服务器列表", "管理实际保存的服务器和局域网游戏。", () -> { requireNoWorld(); nativeScreen(new JoinMultiplayerScreen(mc.screen)); });
        action("native.mmd", "MMD Skin", "完整设置与资源", "完整 MMD 设置 / 生物替换", "包含每种生物的模型替换配置。", () -> openPlatformConfig());
        action("native.model", "MMD Skin", "当前模型 / 基础", "模型设置与快捷槽", "当前模型的全部设置和四个快捷切换槽。", () -> nativeScreen(new ModelSettingsScreen(modelContext(), mc.screen)));
        action("native.animations", "MMD Skin", "当前模型 / 基础", "游戏动作映射", "为走路、待机等状态选择实际 VMD 动画。", () -> nativeScreen(new ModelAnimationScreen(modelContext(), mc.screen)));
        action("native.materials", "MMD Skin", "当前模型 / 基础", "材质显隐", "编辑当前使用模型的全部材质。", () -> nativeScreen(MaterialVisibilityScreen.createForPlayer()));
        action("native.action_wheel", "MMD Skin", "轮盘与动作", "动作轮盘项目", "选择动作轮盘中的实际项目。", () -> nativeScreen(new ActionWheelConfigScreen(mc.screen)));
        action("native.morph_wheel", "MMD Skin", "轮盘与动作", "表情轮盘项目", "选择表情预设及 VPD 文件。", () -> nativeScreen(new MorphWheelConfigScreen(mc.screen)));
        action("native.stage", "MMD Skin", "舞台 / 播放", "舞台与联机会话", "动作分配、邀请、准备和主相机设置。", () -> nativeScreen(new StageSelectScreen()));
        action("native.vr_calibration", "MMD Skin", "当前模型 / VR 校准", "T-Pose 身高与触达校准", "开始真实 VR 追踪校准。", () -> calibrate(modelContext()));
        action("native.controller_debug", "MMD Skin", "VR 联动 / 基础", "手柄输入调试", "显示当前左右手柄按键、扳机和摇杆。", () -> nativeScreen(new VrControllerDebugScreen(mc.screen)));
        if (vive != null) {
            viveScreen("all", "完整设置", "GuiAllSettings");
            viveScreen("controls", "SteamVR 按键与控制", "GuiVRControls");
            viveScreen("radial", "轮盘按键配置", "GuiRadialConfiguration");
            viveScreen("quick_commands", "快捷命令", "GuiQuickCommandEditor");
            viveScreen("keyboard", "键盘与布局", "GuiKeyboardSettings");
            viveScreen("keyboard_layouts", "键盘布局管理与编辑", "GuiActiveKeyboardLayoutSelector");
            viveScreen("blacklist", "服务器黑名单", "GuiBlacklistEditor");
            viveScreen("notifications", "聊天提示音选择", "GuiChatNotificationSelection");
        }
    }
    private void viveScreen(String id, String title, String simpleName) {
        action("native.vive." + id, "Vivecraft", "完整设置与资源", title, "打开 Vivecraft 原生功能界面。", () -> {
            try {
                Class<?> type = Class.forName("org.vivecraft.client.gui.settings." + simpleName);
                Screen screen = (Screen)type.getConstructor(Screen.class).newInstance(mc.screen);
                nativeScreen(screen);
            } catch (ReflectiveOperationException failure) { error("此 Vivecraft 界面无法打开", failure); }
        });
    }
    private void nativeScreen(Screen screen) {
        if (screen == null) { status = "当前没有可配置的模型"; return; }
        flush(); SpatialMenuHost.openNative(screen);
    }
    private void openPlatformConfig() {
        for (String platform : List.of("neoforge", "fabric")) try {
            Class<?> type = Class.forName("com.shiroha.mmdskin." + platform + ".config.ModConfigScreen");
            nativeScreen((Screen)type.getMethod("create", Screen.class).invoke(null, mc.screen)); return;
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException failure) { error("配置界面无法打开", failure); return; }
    }

    @Override public void dispatch(MenuCommand command) {
        if (frameDepth > 0) { afterFrame.addLast(() -> dispatch(command)); return; }
        try {
            flush();
            switch (command.type()) {
                case "menu.resume" -> closeMenu.run();
                case "avatar.select" -> {
                    requireAvatar(command.target());
                    ModelSelectorServices.modelSelection().selectModel(command.target()); selectModelContext(command.target());
                    refreshModelResources(); materialContext = null; status = "已切换模型：" + command.target();
                }
                case "avatar.material" -> changeMaterial(command);
                case "motion.play" -> {
                    requireModel();
                    if (motions.stream().noneMatch(entry -> entry.animId.equals(command.target()))) throw new IllegalArgumentException("动作已失效，请刷新资源");
                    new DefaultActionWheelService().selectAction(command.target()); status = "已播放动作";
                }
                case "motion.stop" -> {
                    requirePlayer(); MmdSkinRendererPlayerHelper.ResetPhysics(mc.player); ActionWheelNetworkHandler.getInstance().syncAnimStop(); status = "已停止动作";
                }
                case "morph.apply" -> {
                    requireModel(); MorphOption morph = morphs.stream().filter(entry -> entry.syncToken().equals(command.target())).findFirst().orElseThrow(() -> new IllegalArgumentException("表情已失效，请刷新资源"));
                    applyMorph(morph); status = "已应用表情：" + morph.displayName();
                }
                case "morph.reset" -> {
                    requireModel(); morphs.stream().filter(MorphOption::resetAction).findFirst().ifPresent(this::applyMorph); status = "已重置表情";
                }
                case "mirror.mode" -> {
                    requirePlayer(); VrMirrorController.Mode mode = VrMirrorController.Mode.valueOf(command.target());
                    if (mode != VrMirrorController.Mode.OFF && !VrMirrorController.isEnabled()) VrMirrorController.toggle();
                    VrMirrorController.setMode(mode);
                    if (VrMirrorController.mode() != mode) throw new IllegalStateException("尚未获取有效 VR 追踪，镜子未开启");
                    status = "镜子：" + mode;
                }
                case "vr.recenter" -> {
                    Class<?> type = Class.forName("org.vivecraft.client_vr.settings.VRSettings");
                    Class<?> option = Class.forName(type.getName() + "$VrOptions");
                    type.getMethod("setOptionValue", option).invoke(type.getField("INSTANCE").get(null), Enum.valueOf((Class)option, "RESET_ORIGIN")); status = "已重新定位 VR 原点";
                }
                case "calibration.start" -> calibrate(command.target());
                case "stage.play" -> startStage(command.target());
                case "stage.stop" -> { MMDCameraController.getInstance().exitStageMode(); status = "已停止舞台"; }
                case "stage.configure" -> nativeScreen(new StageSelectScreen());
                case "stage.ready" -> { requirePlayer(); StageWorkbenchFacade.getInstance().toggleLocalReady(); }
                case "world.open" -> openWorld(command.target());
                case "world.delete" -> deleteWorld(command);
                case "server.add" -> addServer(command);
                case "server.edit" -> editServer(command);
                case "server.delete" -> deleteServer(command);
                case "resources.refresh" -> refresh();
                default -> throw new IllegalArgumentException("未绑定的菜单操作：" + command.type());
            }
            history.add(command); if (history.size() > 128) history.removeFirst();
        } catch (Exception | LinkageError failure) { error("操作未完成", failure); }
    }
    private void changeMaterial(MenuCommand command) {
        requirePlayer(); materials();
        if (materialContext == null || !Objects.equals(command.target(), equippedAvatar())) throw new IllegalArgumentException("请先使用此模型，再修改材质");
        int material = Integer.parseInt(String.valueOf(command.arguments().get("material")));
        boolean visible = Boolean.TRUE.equals(command.arguments().get("visible"));
        for (int i = 0; i < materialRows.size(); i++) if (materialRows.get(i).index() == material) {
            var service = ModelSelectorServices.materialVisibility();
            if (materialRows.get(i).visible() != visible) service.toggleMaterial(materialContext, materialRows, i);
            service.save(materialContext, materialRows);
            if (mmd != null) mmd.selectModel(modelContext());
            status = "已更新材质显隐"; return;
        }
        throw new IllegalArgumentException("材质已经失效，请刷新模型");
    }
    private void applyMorph(MorphOption morph) {
        var selection = ExpressionSelectionCodec.decode(morph.syncToken());
        var local = selection.type() == ExpressionSelection.Type.FILE ? ExpressionSelection.file(morph.filePath()) : selection;
        if (!com.shiroha.mmdskin.expression.ExpressionApplicationService.apply(mc.player, local))
            throw new IllegalStateException("此表情无法应用到当前模型");
        com.shiroha.mmdskin.player.sync.PlayerMorphSyncService.getInstance().syncMorph(morph.syncToken());
    }
    private void calibrate(String model) {
        requirePlayer(); requireAvatar(model);
        if (!Objects.equals(model, equippedAvatar())) throw new IllegalArgumentException("请先使用此模型，再开始校准");
        VrModelSettingsScreen owner = new VrModelSettingsScreen(model, mc.screen);
        nativeScreen(owner);
        if (!owner.startCalibration()) status = "校准尚未开始，请查看模型设置中的提示";
    }
    private void startStage(String id) {
        requirePlayer();
        StagePack pack = stages.stream().filter(stage -> stage.getName().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("舞台包已失效，请刷新资源"));
        var facade = StageWorkbenchFacade.getInstance();
        if (!facade.canStartStage(pack)) throw new IllegalArgumentException("舞台缺少动作，或联机成员尚未准备完成");
        StageConfig config = StageConfig.getInstance();
        facade.savePreferences(id, config.cinematicMode, config.cameraHeightOffset, config.audioVolume);
        if (!facade.startStage(pack, config.cinematicMode, config.cameraHeightOffset, null)) throw new IllegalArgumentException("舞台加载失败，请检查资源");
        closeMenu.run(); status = "已开始舞台：" + id;
    }
    private void openWorld(String id) {
        if (worldBusy(id)) throw new IllegalStateException("该存档正在删除，请等待完成");
        if (!id.isBlank() && id.equals(currentWorldId())) { closeMenu.run(); return; }
        if (id.startsWith("local:")) {
            String levelId = id.substring(6);
            LevelSummary summary = localWorlds.entries().stream().filter(world -> world.getLevelId().equals(levelId)).findFirst().orElse(null);
            if (summary == null || !summary.primaryActionActive()) throw new IllegalArgumentException("该存档不可打开");
            leaveWorld(); mc.createWorldOpenFlows().openWorld(levelId, () -> mc.setScreen(new SelectWorldScreen(new net.minecraft.client.gui.screens.TitleScreen())));
        } else if (id.startsWith("server:")) {
            int index = Integer.parseInt(id.substring(7)); ServerData server = servers.get(index);
            if(!mc.allowsMultiplayer())throw new IllegalStateException("当前账户未允许多人游戏，请检查 Minecraft 账户设置");
            if(!ServerAddress.isValidAddress(server.ip))throw new IllegalArgumentException("服务器地址无效");
            leaveWorld(); ConnectScreen.startConnecting(new JoinMultiplayerScreen(new net.minecraft.client.gui.screens.TitleScreen()), mc, ServerAddress.parseString(server.ip), server, false, null);
        } else throw new IllegalArgumentException("无效世界选择");
    }
    private void leaveWorld() {
        closeMenu.run();serverStatus.reset();
        if(mc.level!=null){
            mc.level.disconnect();
            mc.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(Component.translatable("menu.savingLevel")));
        }
    }
    private void addServer(MenuCommand command) {
        String address = command.target().strip();
        if (!ServerAddress.isValidAddress(address)) throw new IllegalArgumentException("服务器地址无效");
        String name = String.valueOf(command.arguments().getOrDefault("name", address));
        ServerList list = new ServerList(mc); list.load(); list.add(new ServerData(name, address, ServerData.Type.OTHER), false); list.save();
        refresh(); status = "服务器已保存：" + name;
    }
    /** Confirmation carries the original identity; refreshing/reordering must never edit a different row. */
    private int verifiedServer(MenuCommand command, ServerList list) {
        if (!command.target().startsWith("server:")) throw new IllegalArgumentException("无效服务器");
        int index = Integer.parseInt(command.target().substring(7));
        if (index < 0 || index >= list.size()) throw new IllegalStateException("服务器列表已改变，请重新选择");
        ServerData server = list.get(index);
        if (!server.name.equals(command.arguments().get("expectedName")) || !server.ip.equals(command.arguments().get("expectedAddress")))
            throw new IllegalStateException("服务器列表已改变，请重新选择");
        return index;
    }
    private void editServer(MenuCommand command) {
        String name = String.valueOf(command.arguments().getOrDefault("name", "")).strip();
        String address = String.valueOf(command.arguments().getOrDefault("address", "")).strip();
        String validation = serverValidationError(name, address);
        if (!validation.isEmpty()) throw new IllegalArgumentException(validation);
        ServerList list = new ServerList(mc); list.load();
        ServerData server = list.get(verifiedServer(command, list));
        String oldAddress = server.ip; boolean wasFavorite = favorite("server-address:" + oldAddress);
        serverStatus.reset();
        server.name = name; server.ip = address;
        if (!oldAddress.equals(address)) server.setIconBytes(null);
        list.save();
        if (wasFavorite && !oldAddress.equals(address)) {
            if (!favorite("server-address:" + address)) toggleFavorite("server-address:" + address);
            boolean oldStillSaved = false;
            for (int i = 0; i < list.size(); i++) oldStillSaved |= list.get(i).ip.equals(oldAddress);
            if (!oldStillSaved) toggleFavorite("server-address:" + oldAddress);
        }
        refreshWorldCatalog(); status = "服务器已更新：" + name;
    }
    @Override public String serverValidationError(String name, String address) {
        if (name == null || name.isBlank() || name.strip().length() > 128) return "请填写有效的服务器名称";
        if (address == null || address.isBlank() || address.strip().length() > 255 || !ServerAddress.isValidAddress(address.strip())) return "服务器地址无效";
        return "";
    }
    private void deleteServer(MenuCommand command) {
        requireDeleteConfirmation(command);
        ServerList list = new ServerList(mc); list.load();
        ServerData server = list.get(verifiedServer(command, list));
        serverStatus.reset(); list.remove(server); list.save();
        boolean stillSaved = false;
        for (int i = 0; i < list.size(); i++) stillSaved |= list.get(i).ip.equals(server.ip);
        if (!stillSaved && favorite("server-address:" + server.ip)) toggleFavorite("server-address:" + server.ip);
        refreshWorldCatalog(); status = "已从列表删除服务器：" + server.name;
    }
    private static void requireDeleteConfirmation(MenuCommand command) {
        if (!Boolean.TRUE.equals(command.arguments().get("confirmed"))) throw new IllegalArgumentException("请先确认删除");
    }
    private void deleteWorld(MenuCommand command) {
        requireDeleteConfirmation(command); String id = command.target();
        if (!id.startsWith("local:") || !canDeleteWorld(id)) throw new IllegalStateException("该存档正在使用或不可删除，请先退出并刷新列表");
        String levelId = id.substring(6);
        LevelSummary summary = localWorlds.entries().stream().filter(world -> world.getLevelId().equals(levelId)).findFirst().orElseThrow();
        if (!summary.getLevelName().equals(command.arguments().get("expectedName"))) throw new IllegalStateException("存档信息已改变，请重新选择");
        deletingWorlds.add(id); status = "正在删除存档：" + summary.getLevelName();
        // A large world may take seconds to remove. Keep VR rendering and the rest of the menu responsive.
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { WorldDeletion.delete(mc.getLevelSource(), levelId); }
            catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, worldEdits).whenCompleteAsync((unused, failure) -> {
            deletingWorlds.remove(id);
            if (closed) return;
            if (failure == null) {
                if (favorite(id)) toggleFavorite(id);
                status = "已删除存档：" + summary.getLevelName();
            } else error("存档删除失败", failure);
            refreshWorldCatalog();
        }, mc);
    }
    private void requirePlayer() { if (mc.player == null || mc.level == null) throw new IllegalStateException("请先进入世界"); }
    private void requireModel() {
        requirePlayer();
        if (com.shiroha.mmdskin.player.model.PlayerModelResolver.resolve(mc.player) == null)
            throw new IllegalStateException("请先使用 MMD 模型，并等待模型加载完成");
    }
    private void requireNoWorld() { if (mc.level != null) throw new IllegalStateException("请先保存并退出当前世界，再进入存档或服务器管理"); }
    private void requireAvatar(String model) { if (avatars.stream().noneMatch(avatar -> avatar.id().equals(model))) throw new IllegalArgumentException("模型已失效，请刷新资源"); }
    private SettingBinding binding(String id) {
        String canonical = switch (id) {
            case "vr.enabled" -> "mmd.vrEnabled"; case "vr.armIKStrength" -> "mmd.vrArmIKStrength";
            case "mc.masterVolume" -> "mc.soundCategory.master"; case "mc.musicVolume" -> "mc.soundCategory.music";
            case "mc.gamma" -> "mc.options.gamma"; case "mc.renderDistance" -> "mc.options.renderDistance";
            case "mc.simulationDistance" -> "mc.options.simulationDistance"; case "mc.fov" -> "mc.options.fov";
            case "mc.bobView" -> "mc.options.viewBobbing"; case "mc.showSubtitles" -> "mc.options.showSubtitles";
            default -> id;
        };
        SettingBinding result = bindings.get(canonical);
        if (result == null) throw new IllegalArgumentException("不可用的设置：" + id);
        return result;
    }
    private void error(String message, Throwable error) {
        Throwable cause = error instanceof java.lang.reflect.InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : error;
        status = message + "：" + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        LOGGER.warn("[Spatial UI] {}", status, cause);
    }
    @Override public void close() { if (!closed) { flush(); closed = true; localWorlds.close(); images.close(); portraits.close(); serverStatus.close(); worldEdits.shutdown(); } }
}
