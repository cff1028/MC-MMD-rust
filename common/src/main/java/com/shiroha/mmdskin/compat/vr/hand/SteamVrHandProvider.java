package com.shiroha.mmdskin.compat.vr.hand;

import com.shiroha.mmdskin.compat.vr.VivecraftReflectionBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

/** Optional SteamVR skeleton reader. Vivecraft owns OpenVR initialization and action polling. */
public final class SteamVrHandProvider {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String OPENVR_PROVIDER = "org.vivecraft.client_vr.provider.openvr_lwjgl.MCOpenVR";
    private static final long MAX_SAMPLE_AGE_NS = 250_000_000L;
    private static final RawHand[] EMPTY = new RawHand[2];
    private static volatile RawHand[] hands = EMPTY;
    private static volatile String diagnostic = "SteamVR skeletal input has not been polled";
    private static Api api;
    private static Object provider;
    private static final long[] actionHandles = new long[2];
    private static final ReferenceCache[] references = { new ReferenceCache(), new ReferenceCache() };
    private static long nextHandleLookup, firstPoll, sequence;
    private static int reportedHands;
    private static boolean failureLogged, unavailableLogged;

    private SteamVrHandProvider() {}

    /**
     * Model-space OpenVR bones: 31 * [x,y,z,qx,qy,qz,qw], metres/right-handed.
     * Arrays are read-only snapshots. Bones/reference or curl/splay arrays can be null independently.
     * trackingLevel is accuracy (0 estimated, 1 partial, 2 full), not controller-vs-bare-hand mode.
     */
    public record RawHand(long sampleId, long sampledAtNanos, long activeOrigin,
                          int trackedDeviceIndex, boolean physicalLeft, int trackingLevel,
                          float[] bones, float[] referenceOpenHand, float[] curls, float[] splays) {}

    /** Index 0/1 matches Vivecraft controller poses, including reverse-hands and live source changes. */
    public static RawHand readHand(int controllerIndex) {
        if (controllerIndex < 0 || controllerIndex > 1
                || VivecraftReflectionBridge.getRenderFrameId() == Long.MIN_VALUE) return null;
        RawHand result = hands[controllerIndex];
        return result != null && isFresh(result.sampledAtNanos(), System.nanoTime()) ? result : null;
    }

    public static String getDiagnostic() { return diagnostic; }

    /** Invoked at MCOpenVR.updatePose RETURN, after its sole UpdateActionState and pose refresh. */
    public static void poll(Object currentProvider) {
        if (!isOpenVrProvider(currentProvider)) return; // NULLVR never resolves/calls OpenVR APIs.
        try {
            if (api == null) api = new Api(currentProvider.getClass());
            if (provider != currentProvider) {
                reset();
                provider = currentProvider;
            }
            if (!api.inputInitialized.getBoolean(currentProvider) || !api.vrRunning.getBoolean(null)) {
                hands = EMPTY;
                diagnostic = "SteamVR input or VR session is inactive";
                return;
            }
            long now = System.nanoTime();
            if (firstPoll == 0) firstPoll = now;
            long sampleId = ++sequence;
            RawHand[] next = new RawHand[2];
            try (AutoCloseable stack = (AutoCloseable) api.stackPush.invoke(null)) {
                if (now >= nextHandleLookup && (actionHandles[0] == 0 || actionHandles[1] == 0)) {
                    nextHandleLookup = now + 1_000_000_000L;
                    for (int side = 0; side < 2; side++) if (actionHandles[side] == 0) {
                        LongBuffer handle = (LongBuffer) api.stackCallocLong.invoke(stack, 1);
                        int error = (int) api.getActionHandle.invoke(null,
                                side == 0 ? SteamVrHandManifest.LEFT_ACTION : SteamVrHandManifest.RIGHT_ACTION, handle);
                        if (error == 0) actionHandles[side] = handle.get(0);
                    }
                }
                for (int side = 0; side < 2; side++) {
                    if (actionHandles[side] == 0) continue;
                    RawHand hand = api.read(currentProvider, stack, side, actionHandles[side], sampleId, now);
                    if (hand == null) continue;
                    int index = api.controllerIndex(currentProvider, hand.trackedDeviceIndex());
                    if (index >= 0) {
                        next[index] = hand;
                        if ((reportedHands & (1 << side)) == 0) {
                            LOGGER.info("[VR hands] SteamVR {} skeletal data active on controller {}: accuracy={}, {}. "
                                            + "Runtime source changes and tracking loss are handled automatically.",
                                    hand.physicalLeft() ? "left" : "right", index, hand.trackingLevel(),
                                    hand.bones() == null ? "summary" : "31 model-space bones");
                            reportedHands |= 1 << side;
                        }
                    }
                }
            }
            hands = next;
            if (next[0] != null || next[1] != null) {
                diagnostic = "SteamVR skeleton active: controller 0=" + describe(next[0]) + ", controller 1=" + describe(next[1]);
            } else {
                diagnostic = "No active tracked SteamVR skeletal binding; using controller hand poses";
                if (!unavailableLogged && now - firstPoll > 5_000_000_000L) {
                    LOGGER.info("[VR hands] No active tracked skeletal data. Controller posing remains available. "
                            + "For a saved custom SteamVR binding, bind the MMD Skin left/right skeleton actions "
                            + "in the Global action set; devices without skeletal data need no changes.");
                    unavailableLogged = true;
                }
            }
        } catch (Throwable failure) {
            hands = EMPTY;
            diagnostic = "Optional SteamVR skeleton reader unavailable: " + failure.getClass().getSimpleName();
            if (!failureLogged) {
                LOGGER.warn("[VR hands] Optional skeletal input unavailable; controller posing remains available", failure);
                failureLogged = true;
            }
        }
    }

    /** No native call: safe during provider shutdown, failed initialization, or NULLVR tests. */
    public static void reset() {
        hands = EMPTY;
        provider = null;
        actionHandles[0] = actionHandles[1] = 0;
        references[0].clear();
        references[1].clear();
        nextHandleLookup = firstPoll = 0;
        reportedHands = 0;
        unavailableLogged = false;
        diagnostic = "SteamVR skeletal input has not been polled";
    }

    static boolean isOpenVrProvider(Object value) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass())
            if (OPENVR_PROVIDER.equals(type.getName())) return true;
        return false;
    }

    static boolean isFresh(long timestamp, long now) {
        return now >= timestamp && now - timestamp <= MAX_SAMPLE_AGE_NS;
    }

    static boolean validBones(float[] data) {
        if (data == null || data.length != 31 * 7) return false;
        for (int bone = 0; bone < 31; bone++) {
            int offset = bone * 7;
            for (int i = 0; i < 7; i++) if (!Float.isFinite(data[offset + i])) return false;
            float length = 0;
            for (int i = 3; i < 7; i++) length += data[offset + i] * data[offset + i];
            if (!Float.isFinite(length) || length < 1.0e-6f) return false;
        }
        return true;
    }

    static float[] finiteSummary(float[] values, int count) {
        if (values == null || values.length != count) return null;
        float[] result = values.clone();
        for (int i = 0; i < count; i++) {
            if (!Float.isFinite(result[i])) return null;
            result[i] = Math.max(0, Math.min(1, result[i]));
        }
        return result;
    }

    static int matchController(int device, int index0, boolean openVr0, int index1, boolean openVr1) {
        if (device < 0) return -1;
        if (openVr0 && device == index0) return 0;
        return openVr1 && device == index1 ? 1 : -1;
    }

    static boolean validTrackedPose(boolean active, long origin, boolean validPose, boolean connected) {
        // A skeleton can remain bActive while a camera-tracked hand leaves the tracking volume.
        return active && origin != 0 && validPose && connected;
    }

    record ReferenceSource(long activeOrigin, int deviceIndex, int trackingLevel) {}

    /** Steam Link can change hand/controller modes without assigning a new action origin. */
    static final class ReferenceCache {
        private ReferenceSource source;
        private float[] bones;

        float[] forSource(ReferenceSource current) {
            if (!current.equals(source)) {
                source = current;
                bones = null;
            }
            return bones;
        }

        void store(float[] value) { bones = value; }
        void clear() { source = null; bones = null; }
    }

    private static String describe(RawHand hand) {
        return hand == null ? "controller fallback" : (hand.physicalLeft ? "left" : "right")
                + "/accuracy " + hand.trackingLevel + (hand.bones == null ? "/summary" : "/31 bones");
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) try {
            Field result = current.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (NoSuchFieldException ignored) {}
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static final class Api {
        final Field inputInitialized, vrRunning, sources, sourceType, sourceIndex, poses;
        final Method stackPush, stackCallocLong, stackCallocInt, getActionHandle,
                actionAlloc, actionActive, actionOrigin, getAction, getOrigin, originAlloc, originDevice,
                getTracking, getCount, getBones, getReference, getSummary, summaryAlloc, summaryCurl, summarySplay,
                bonesAlloc, address, memoryView, poseGet, poseValid, poseConnected;
        final int boneSize, bonePosition, boneOrientation, qx, qy, qz, qw;

        Api(Class<?> providerClass) throws ReflectiveOperationException {
            ClassLoader loader = providerClass.getClassLoader();
            Class<?> vrInput = Class.forName("org.lwjgl.openvr.VRInput", false, loader);
            Class<?> stack = Class.forName("org.lwjgl.system.MemoryStack", false, loader);
            Class<?> action = Class.forName("org.lwjgl.openvr.InputSkeletalActionData", false, loader);
            Class<?> origin = Class.forName("org.lwjgl.openvr.InputOriginInfo", false, loader);
            Class<?> bones = Class.forName("org.lwjgl.openvr.VRBoneTransform", false, loader);
            Class<?> bonesBuffer = Class.forName("org.lwjgl.openvr.VRBoneTransform$Buffer", false, loader);
            Class<?> summary = Class.forName("org.lwjgl.openvr.VRSkeletalSummaryData", false, loader);
            Class<?> quaternion = Class.forName("org.lwjgl.openvr.HmdQuaternionf", false, loader);
            Class<?> pose = Class.forName("org.lwjgl.openvr.TrackedDevicePose", false, loader);
            Class<?> poseBuffer = Class.forName("org.lwjgl.openvr.TrackedDevicePose$Buffer", false, loader);
            Class<?> source = Class.forName("org.vivecraft.client_vr.provider.DeviceSource", false, loader);
            inputInitialized = field(providerClass, "inputInitialized");
            vrRunning = field(Class.forName("org.vivecraft.client_vr.VRState", false, loader), "VR_RUNNING");
            sources = field(providerClass, "deviceSource");
            sourceType = field(source, "source");
            sourceIndex = field(source, "deviceIndex");
            poses = field(providerClass, "trackedDevicePoses");
            poseGet = poseBuffer.getMethod("get", int.class);
            poseValid = pose.getMethod("bPoseIsValid");
            poseConnected = pose.getMethod("bDeviceIsConnected");
            stackPush = stack.getMethod("stackPush");
            stackCallocLong = stack.getMethod("callocLong", int.class);
            stackCallocInt = stack.getMethod("callocInt", int.class);
            getActionHandle = vrInput.getMethod("VRInput_GetActionHandle", CharSequence.class, LongBuffer.class);
            actionAlloc = action.getMethod("calloc", stack);
            actionActive = action.getMethod("bActive");
            actionOrigin = action.getMethod("activeOrigin");
            getAction = vrInput.getMethod("VRInput_GetSkeletalActionData", long.class, action);
            originAlloc = origin.getMethod("calloc", stack);
            originDevice = origin.getMethod("trackedDeviceIndex");
            getOrigin = vrInput.getMethod("VRInput_GetOriginTrackedDeviceInfo", long.class, origin);
            getTracking = vrInput.getMethod("VRInput_GetSkeletalTrackingLevel", long.class, IntBuffer.class);
            getCount = vrInput.getMethod("VRInput_GetBoneCount", long.class, IntBuffer.class);
            getBones = vrInput.getMethod("VRInput_GetSkeletalBoneData", long.class, int.class, int.class, bonesBuffer);
            getReference = vrInput.getMethod("VRInput_GetSkeletalReferenceTransforms", long.class, int.class, int.class, bonesBuffer);
            getSummary = vrInput.getMethod("VRInput_GetSkeletalSummaryData", long.class, int.class, summary);
            summaryAlloc = summary.getMethod("calloc", stack);
            summaryCurl = summary.getMethod("flFingerCurl", int.class);
            summarySplay = summary.getMethod("flFingerSplay", int.class);
            bonesAlloc = bones.getMethod("calloc", int.class, stack);
            address = bonesBuffer.getMethod("address");
            memoryView = Class.forName("org.lwjgl.system.MemoryUtil", false, loader).getMethod("memByteBuffer", long.class, int.class);
            boneSize = bones.getField("SIZEOF").getInt(null);
            bonePosition = bones.getField("POSITION").getInt(null);
            boneOrientation = bones.getField("ORIENTATION").getInt(null);
            qx = quaternion.getField("X").getInt(null); qy = quaternion.getField("Y").getInt(null);
            qz = quaternion.getField("Z").getInt(null); qw = quaternion.getField("W").getInt(null);
        }

        int controllerIndex(Object provider, int device) throws ReflectiveOperationException {
            Object[] devices = (Object[]) sources.get(provider);
            if (devices.length < 2) return -1;
            return matchController(device, sourceIndex.getInt(devices[0]), "OPENVR".equals(String.valueOf(sourceType.get(devices[0]))),
                    sourceIndex.getInt(devices[1]), "OPENVR".equals(String.valueOf(sourceType.get(devices[1]))));
        }

        RawHand read(Object provider, Object stack, int side, long handle, long sample, long now)
                throws ReflectiveOperationException {
            Object action = actionAlloc.invoke(null, stack);
            if ((int) getAction.invoke(null, handle, action) != 0 || !(boolean) actionActive.invoke(action)) {
                clearReference(side);
                return null;
            }
            long activeOrigin = (long) actionOrigin.invoke(action);
            if (activeOrigin == 0) { clearReference(side); return null; }
            Object origin = originAlloc.invoke(null, stack);
            if ((int) getOrigin.invoke(null, activeOrigin, origin) != 0) return null;
            int device = (int) originDevice.invoke(origin);
            if (device < 0 || device >= 64 || controllerIndex(provider, device) < 0) return null;
            Object pose = poseGet.invoke(poses.get(provider), device);
            if (!validTrackedPose(true, activeOrigin, (boolean) poseValid.invoke(pose), (boolean) poseConnected.invoke(pose))) {
                clearReference(side);
                return null;
            }
            IntBuffer tracking = (IntBuffer) stackCallocInt.invoke(stack, 1);
            if ((int) getTracking.invoke(null, handle, tracking) != 0 || tracking.get(0) < 0 || tracking.get(0) > 2) return null;
            float[] reference = references[side].forSource(new ReferenceSource(activeOrigin, device, tracking.get(0)));
            float[] transforms = null;
            IntBuffer count = (IntBuffer) stackCallocInt.invoke(stack, 1);
            if ((int) getCount.invoke(null, handle, count) == 0 && count.get(0) == 31) {
                Object buffer = bonesAlloc.invoke(null, 31, stack);
                // Model space, WithController: the runtime's measured hand, including real pinch/contact.
                if ((int) getBones.invoke(null, handle, 0, 0, buffer) == 0) transforms = copyBones(buffer);
                if (reference == null && (int) getReference.invoke(null, handle, 0, 1, buffer) == 0) {
                    reference = copyBones(buffer);
                    references[side].store(reference);
                }
            }
            Object summary = summaryAlloc.invoke(null, stack);
            float[] curls = null, splays = null;
            // FromDevice first; estimated runtimes may expose only FromAnimation.
            if ((int) getSummary.invoke(null, handle, 1, summary) == 0
                    || (int) getSummary.invoke(null, handle, 0, summary) == 0) {
                curls = new float[5]; splays = new float[4];
                for (int i = 0; i < 5; i++) curls[i] = (float) summaryCurl.invoke(summary, i);
                for (int i = 0; i < 4; i++) splays[i] = (float) summarySplay.invoke(summary, i);
                curls = finiteSummary(curls, 5); splays = finiteSummary(splays, 4);
            }
            if (transforms == null && curls == null) return null;
            return new RawHand(sample, now, activeOrigin, device, side == 0, tracking.get(0),
                    transforms, reference, curls, splays);
        }

        private float[] copyBones(Object buffer) throws ReflectiveOperationException {
            ByteBuffer bytes = ((ByteBuffer) memoryView.invoke(null, (long) address.invoke(buffer), boneSize * 31)).order(ByteOrder.nativeOrder());
            float[] result = new float[31 * 7];
            for (int i = 0; i < 31; i++) {
                int base = i * boneSize, destination = i * 7;
                for (int axis = 0; axis < 3; axis++) result[destination + axis] = bytes.getFloat(base + bonePosition + axis * 4);
                result[destination + 3] = bytes.getFloat(base + boneOrientation + qx);
                result[destination + 4] = bytes.getFloat(base + boneOrientation + qy);
                result[destination + 5] = bytes.getFloat(base + boneOrientation + qz);
                result[destination + 6] = bytes.getFloat(base + boneOrientation + qw);
            }
            return validBones(result) ? result : null;
        }

        private void clearReference(int side) {
            references[side].clear();
        }
    }
}
