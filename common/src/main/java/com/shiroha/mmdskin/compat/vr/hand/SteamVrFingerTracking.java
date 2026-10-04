package com.shiroha.mmdskin.compat.vr.hand;

import com.shiroha.mmdskin.compat.vr.keyboard.VivecraftKeyboardBridge;
import org.apache.logging.log4j.LogManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.LongBuffer;

/** Positional finger contacts use the skeletal action's pose, never the controller's grip offset. */
public final class SteamVrFingerTracking {
    private static final int[] TIP_BONES = {5, 10, 15, 20, 25};
    private static volatile Sample[] samples = new Sample[2];
    private static Api api;
    private static Object provider;
    private static final long[] handles = new long[2];
    private static long nextLookup;
    private static boolean warned;

    private record Sample(long sampledAtNanos, Vector3f[] positions) {}

    /** Called once after Vivecraft has polled poses and the normal skeletal hand reader has run. */
    public static void poll(Object current) {
        if (!SteamVrHandProvider.isOpenVrProvider(current)) return;
        if (!VivecraftKeyboardBridge.isShowing()) { samples = new Sample[2]; return; }
        SteamVrHandProvider.RawHand[] hands = {SteamVrHandProvider.readHand(0), SteamVrHandProvider.readHand(1)};
        if (hands[0] == null && hands[1] == null) { samples = new Sample[2]; return; }
        Sample[] next = new Sample[2];
        try {
            if (api == null) api = new Api(current.getClass());
            if (provider != current) { reset(); provider = current; }
            long now = System.nanoTime();
            try (AutoCloseable stack = (AutoCloseable)api.stackPush.invoke(null)) {
                if (now >= nextLookup && (handles[0] == 0 || handles[1] == 0)) {
                    nextLookup = now + 1_000_000_000L;
                    for (int side = 0; side < 2; side++) if (handles[side] == 0) {
                        LongBuffer result = (LongBuffer)api.callocLong.invoke(stack, 1);
                        if ((int)api.getHandle.invoke(null, side == 0 ? SteamVrHandManifest.LEFT_ACTION
                                : SteamVrHandManifest.RIGHT_ACTION, result) == 0) handles[side] = result.get(0);
                    }
                }
                for (int index = 0; index < 2; index++) {
                    SteamVrHandProvider.RawHand hand = hands[index];
                    // Estimated poses are button animations, not measured finger contact locations.
                    if (hand == null || hand.trackingLevel() < 1 || hand.bones() == null) continue;
                    long handle = handles[hand.physicalLeft() ? 0 : 1];
                    if (handle == 0) continue;
                    Object action = api.poseAlloc.invoke(null, stack);
                    // Match Vivecraft's Standing universe and next-frame prediction. Valve specifies
                    // this same skeletal handle: a controller raw pose misses the skeleton origin.
                    int error = (int)api.getPose.invoke(null, handle, 1, action, api.poseSize, 0L);
                    if (error != 0 || !(boolean)api.poseActive.invoke(action)) continue;
                    Object pose = api.poseValue.invoke(action);
                    if (!(boolean)api.poseValid.invoke(pose) || !(boolean)api.poseConnected.invoke(pose)) continue;
                    Matrix4f tracking = (Matrix4f)api.convertMatrix.invoke(null,
                            api.poseMatrix.invoke(pose), new Matrix4f());
                    Vector3f[] tips = transformTips(hand.bones(), tracking);
                    if (tips != null) next[index] = new Sample(hand.sampledAtNanos(), tips);
                }
            }
        } catch (Throwable failure) {
            if (!warned) {
                warned = true;
                LogManager.getLogger().warn("[VR keyboard] Positional skeleton unavailable; ray and whole-hand typing remain available", failure);
            }
        }
        samples = next;
    }

    /** Snapshot transformed into Vivecraft's current room space, including origin and walking offsets. */
    public static Vector3f[] readRoomTips(Object current, int index, Vector3fc roomAimPosition) {
        if (index < 0 || index > 1 || current != provider || api == null || roomAimPosition == null) return null;
        Sample value = samples[index];
        if (value == null || !SteamVrHandProvider.isFresh(value.sampledAtNanos, System.nanoTime())) return null;
        try {
            Object[] rawAim = (Object[])api.rawAimSource.get(current);
            Vector3f correction = new Vector3f(roomAimPosition).sub((Vector3fc)rawAim[index]);
            Vector3f[] result = new Vector3f[value.positions.length];
            for (int i = 0; i < result.length; i++) result[i] = new Vector3f(value.positions[i]).add(correction);
            return result;
        } catch (ReflectiveOperationException | RuntimeException failure) { return null; }
    }

    static Vector3f[] transformTips(float[] bones, Matrix4fc tracking) {
        if (!SteamVrHandProvider.validBones(bones) || tracking == null || !tracking.isFinite()) return null;
        Vector3f[] result = new Vector3f[TIP_BONES.length];
        for (int i = 0; i < result.length; i++) {
            int base = TIP_BONES[i] * 7;
            result[i] = tracking.transformPosition(new Vector3f(bones[base], bones[base + 1], bones[base + 2]));
            if (!result[i].isFinite()) return null;
        }
        return result;
    }

    public static void reset() {
        samples = new Sample[2]; provider = null; handles[0] = handles[1] = 0; nextLookup = 0;
    }

    private static final class Api {
        final Method stackPush, callocLong, getHandle, poseAlloc, getPose, poseActive, poseValue,
                poseValid, poseConnected, poseMatrix, convertMatrix;
        final Field rawAimSource;
        final int poseSize;

        Api(Class<?> providerClass) throws ReflectiveOperationException {
            ClassLoader loader = providerClass.getClassLoader();
            Class<?> stack = Class.forName("org.lwjgl.system.MemoryStack", false, loader);
            Class<?> input = Class.forName("org.lwjgl.openvr.VRInput", false, loader);
            Class<?> action = Class.forName("org.lwjgl.openvr.InputPoseActionData", false, loader);
            Class<?> pose = Class.forName("org.lwjgl.openvr.TrackedDevicePose", false, loader);
            Class<?> matrix = Class.forName("org.lwjgl.openvr.HmdMatrix34", false, loader);
            stackPush = stack.getMethod("stackPush"); callocLong = stack.getMethod("callocLong", int.class);
            getHandle = input.getMethod("VRInput_GetActionHandle", CharSequence.class, LongBuffer.class);
            poseAlloc = action.getMethod("calloc", stack); poseSize = action.getField("SIZEOF").getInt(null);
            getPose = input.getMethod("VRInput_GetPoseActionDataForNextFrame", long.class, int.class, action, int.class, long.class);
            poseActive = action.getMethod("bActive"); poseValue = action.getMethod("pose");
            poseValid = pose.getMethod("bPoseIsValid"); poseConnected = pose.getMethod("bDeviceIsConnected");
            poseMatrix = pose.getMethod("mDeviceToAbsoluteTracking");
            convertMatrix = Class.forName("org.vivecraft.client_vr.provider.openvr_lwjgl.OpenVRUtil", false, loader)
                    .getMethod("convertSteamVRMatrix3ToMatrix4f", matrix, Matrix4f.class);
            rawAimSource = Class.forName("org.vivecraft.client_vr.provider.MCVR", false, loader).getDeclaredField("aimSource");
            rawAimSource.setAccessible(true);
        }
    }

    private SteamVrFingerTracking() {}
}
