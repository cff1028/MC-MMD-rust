package com.shiroha.mmdskin.compat.vr.mirror;

import com.shiroha.mmdskin.compat.iris.IrisCompat;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;

/** Optional Sodium 0.6 view lists. Geometry/storage remain owned by the normal world renderer. */
public final class SodiumMirrorCompat {
    private static final Logger LOGGER = LogManager.getLogger();
    private static Bindings bindings;
    private static boolean resolved;
    private static RuntimeException unsupported;
    private static boolean reported;
    private static Scope active;
    private static WeakReference<Object> pendingOwner = new WeakReference<>(null);
    private static SodiumMirrorBuildQueue<Object> pending = new SodiumMirrorBuildQueue<>();

    private SodiumMirrorCompat() {}

    public static Scope begin() {
        Bindings api = resolve();
        if (api == null) return new Scope();
        if (active != null) throw new IllegalStateException("Nested Sodium mirror view");
        try {
            Object renderer = api.instance.invoke(null);
            if (renderer == null) throw new IllegalStateException("Sodium world renderer is unavailable");
            Object manager = api.manager.get(renderer);
            if (manager == null) throw new IllegalStateException("Sodium section manager is unavailable");
            Scope scope = new Scope(api, renderer, manager);
            api.entityCulling.setBoolean(renderer, false);
            active = scope;
            if (pendingOwner.get() != manager) {
                pendingOwner = new WeakReference<>(manager);
                pending = new SodiumMirrorBuildQueue<>();
            }
            return scope;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw failure("Unable to scope Sodium mirror rendering", failure);
        }
    }

    /** Returns false when Sodium is absent, allowing the vanilla mirror collector to run. */
    public static boolean prepare(Camera camera, Frustum frustum) {
        Scope scope = active;
        if (scope == null) return false;
        Bindings api = scope.api;
        try {
            Map<?, ?> sections = (Map<?, ?>) api.sections.get(scope.manager);
            Vec3 eye = camera.getPosition();
            SectionPos cameraSection = SectionPos.of(camera.getBlockPosition());
            double distance = api.renderDistance.getInt(scope.manager) * 16.0;
            IdentityHashMap<Object, RegionList> regions = new IdentityHashMap<>();
            ArrayList<MissingSection> missing = new ArrayList<>();
            for (Object section : sections.values()) {
                if ((boolean) api.disposed.invoke(section)) continue;
                int x = (int) api.originX.invoke(section);
                int y = (int) api.originY.invoke(section);
                int z = (int) api.originZ.invoke(section);
                if (!withinDistance(eye, x, y, z, distance)
                        || !frustum.isVisible(new AABB(x - 1.125, y - 1.125, z - 1.125,
                        x + 17.125, y + 17.125, z + 17.125))) continue;
                Object update = api.pendingUpdate.invoke(section);
                if (update != null && SodiumMirrorBuildQueue.isGeometryUpdate(((Enum<?>) update).name())
                        && api.token.invoke(section) == null) {
                    missing.add(new MissingSection(section, eye.distanceToSqr(x + 8, y + 8, z + 8)));
                }
                if ((int) api.flags.invoke(section) == 0) continue;
                Object region = api.region.invoke(section);
                RegionList regionList = regions.get(region);
                if (regionList == null) {
                    Object list = api.listConstructor.newInstance(region);
                    // Never call region.getRenderList(): its mutable arrays belong to the eye's graph.
                    int rx = (int) api.regionX.invoke(region);
                    int ry = (int) api.regionY.invoke(region);
                    int rz = (int) api.regionZ.invoke(region);
                    int regionDistance = Math.abs(rx - (cameraSection.x() >> 3))
                            + Math.abs(ry - (cameraSection.y() >> 2)) + Math.abs(rz - (cameraSection.z() >> 3));
                    regionList = new RegionList(list, regionDistance);
                    regions.put(region, regionList);
                }
                api.add.invoke(regionList.list, section);
            }
            missing.sort(Comparator.comparingDouble(MissingSection::distance));
            for (MissingSection section : missing) pending.offer(section.section);
            ArrayList<RegionList> sortedRegions = new ArrayList<>(regions.values());
            sortedRegions.sort(Comparator.comparingInt(RegionList::distance));
            ObjectArrayList<Object> sorted = new ObjectArrayList<>(sortedRegions.size());
            int[] scratch = new int[api.regionSize];
            for (RegionList region : sortedRegions) {
                api.sort.invoke(region.list, cameraSection, scratch);
                sorted.add(region.list);
            }
            Object lists = api.sortedConstructor.newInstance(sorted);
            api.lists.set(scope.manager, lists);
            // This only marks sprites active; it does not advance animation, rebuild, or upload chunks.
            api.tickSprites.invoke(scope.manager);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw failure("Unable to prepare Sodium mirror visibility", failure);
        }
    }

    /** Called at normal Sodium updateChunks HEAD, after its own view/task lists have been prepared. */
    public static void queueMissing(Object manager) {
        if (active != null || manager == null || bindings == null) return;
        var level = Minecraft.getInstance().level;
        if (level == null || !VrMirrorController.isEnabled() || VrMirrorController.mode() != VrMirrorController.Mode.HIGH
                || pendingOwner.get() != manager || unsupported != null) {
            clearPending();
            return;
        }
        if (IrisCompat.isRenderingShadows()) return;
        Bindings api = bindings;
        try {
            Map<?, ?> tasks = (Map<?, ?>) api.tasks.get(manager);
            pending.drain(level.getGameTime(), System::nanoTime, section -> {
                try {
                    if ((boolean) api.disposed.invoke(section) || api.token.invoke(section) != null) return false;
                    Object type = api.pendingUpdate.invoke(section);
                    // Preserve Sodium's existing build priority; never trigger a new sort or promote work.
                    if (type == null || !SodiumMirrorBuildQueue.isGeometryUpdate(((Enum<?>) type).name())) return false;
                    Queue<?> queue = (Queue<?>) tasks.get(type);
                    if (queue == null || queue.size() >= (int) api.maximumQueue.invoke(type)) return false;
                    // Sodium accepts a null pending type when consuming a queue: duplicate entries
                    // could therefore submit the same section twice after the first clears its type.
                    for (Object existing : tasks.values()) if (((Queue<?>) existing).contains(section)) return false;
                    return true;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            }, section -> {
                try {
                    Object type = api.pendingUpdate.invoke(section);
                    @SuppressWarnings("unchecked") Queue<Object> queue = (Queue<Object>) tasks.get(type);
                    queue.add(section);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // Never throw out of the main eye's update. Future HIGH passes fail into its LOW fallback.
            unsupported = failure("Unable to queue missing Sodium mirror geometry", failure);
        }
    }

    /** Mirror release/world teardown drops auxiliary requests without touching Sodium's own queues. */
    public static void clearPending() {
        if (pendingOwner.get() == null && pending.pendingCount() == 0) return;
        pendingOwner = new WeakReference<>(null);
        pending = new SodiumMirrorBuildQueue<>();
    }

    static boolean withinDistance(Vec3 eye, int x, int y, int z, double distance) {
        double dx = Math.max(x - eye.x, Math.max(0, eye.x - (x + 16)));
        double dy = Math.max(y - eye.y, Math.max(0, eye.y - (y + 16)));
        double dz = Math.max(z - eye.z, Math.max(0, eye.z - (z + 16)));
        return dx * dx + dz * dz < distance * distance && dy < distance;
    }

    public static final class Scope implements AutoCloseable {
        private final Bindings api;
        private final Object renderer, manager, originalLists;
        private final boolean originalCulling;
        private boolean closed;

        private Scope() { api = null; renderer = manager = originalLists = null; originalCulling = false; }
        private Scope(Bindings api, Object renderer, Object manager) throws IllegalAccessException {
            this.api = api; this.renderer = renderer; this.manager = manager;
            originalLists = api.lists.get(manager);
            originalCulling = api.entityCulling.getBoolean(renderer);
        }

        @Override public void close() {
            if (closed || api == null) return;
            closed = true;
            Throwable error = null;
            try { api.lists.set(manager, originalLists); }
            catch (ReflectiveOperationException | RuntimeException problem) { error = problem; }
            try { api.entityCulling.setBoolean(renderer, originalCulling); }
            catch (ReflectiveOperationException | RuntimeException problem) {
                if (error == null) error = problem; else error.addSuppressed(problem);
            } finally { if (active == this) active = null; }
            if (error != null) throw failure("Unable to restore Sodium main view", error);
        }
    }

    private static Bindings resolve() {
        if (unsupported != null) throw unsupported;
        if (resolved) return bindings;
        resolved = true;
        Class<?> world;
        try { world = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer"); }
        catch (ClassNotFoundException absent) { return null; }
        catch (LinkageError problem) { throw unsupported = failure("Unable to load optional Sodium mirror support", problem); }
        try { return bindings = new Bindings(world); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError problem) {
            throw unsupported = failure("This Sodium version does not expose the supported mirror view layout", problem);
        }
    }

    private static RuntimeException failure(String message, Throwable problem) {
        if (!reported) { reported = true; LOGGER.warn("{}; HIGH mirror must fall back to LOW", message, problem); }
        return new IllegalStateException(message, problem);
    }

    private record RegionList(Object list, int distance) {}
    private record MissingSection(Object section, double distance) {}

    private static final class Bindings {
        final Method instance, originX, originY, originZ, disposed, built, flags, region, token, pendingUpdate;
        final Method add, sort, regionX, regionY, regionZ, tickSprites, maximumQueue;
        final Field manager, entityCulling, sections, lists, tasks, renderDistance;
        final Constructor<?> listConstructor, sortedConstructor;
        final int regionSize;

        Bindings(Class<?> world) throws ReflectiveOperationException {
            instance = world.getMethod("instanceNullable");
            manager = field(world, "renderSectionManager");
            entityCulling = field(world, "useEntityCulling");
            Class<?> managerType = manager.getType();
            sections = field(managerType, "sectionByPosition");
            lists = field(managerType, "renderLists");
            tasks = field(managerType, "taskLists");
            renderDistance = field(managerType, "renderDistance");
            tickSprites = managerType.getMethod("tickVisibleRenders");
            ClassLoader loader = world.getClassLoader();
            Class<?> section = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", false, loader);
            Class<?> regionType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion", false, loader);
            Class<?> list = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList", false, loader);
            Class<?> update = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType", false, loader);
            originX = section.getMethod("getOriginX"); originY = section.getMethod("getOriginY"); originZ = section.getMethod("getOriginZ");
            disposed = section.getMethod("isDisposed"); built = section.getMethod("isBuilt"); flags = section.getMethod("getFlags");
            region = section.getMethod("getRegion"); token = section.getMethod("getTaskCancellationToken");
            pendingUpdate = section.getMethod("getPendingUpdate"); maximumQueue = update.getMethod("getMaximumQueueSize");
            listConstructor = list.getConstructor(regionType);
            add = list.getMethod("add", section); sort = list.getMethod("sortSections", SectionPos.class, int[].class);
            regionX = regionType.getMethod("getX"); regionY = regionType.getMethod("getY"); regionZ = regionType.getMethod("getZ");
            regionSize = regionType.getField("REGION_SIZE").getInt(null);
            if (regionSize != 256 || regionType.getField("REGION_WIDTH").getInt(null) != 8
                    || regionType.getField("REGION_HEIGHT").getInt(null) != 4
                    || regionType.getField("REGION_LENGTH").getInt(null) != 8)
                throw new IllegalStateException("Unexpected Sodium render region dimensions");
            sortedConstructor = lists.getType().getDeclaredConstructor(ObjectArrayList.class);
            sortedConstructor.setAccessible(true);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
