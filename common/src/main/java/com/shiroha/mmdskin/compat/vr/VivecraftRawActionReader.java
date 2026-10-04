package com.shiroha.mmdskin.compat.vr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reads native samples without consulting or mutating Vivecraft's game-binding state. */
final class VivecraftRawActionReader {
    private final Field type, digitalData, analogData;
    private final Field digitalActive, digitalState, digitalOrigin;
    private final Field analogActive, analogX, analogY, analogZ, analogOrigin;
    private final Method handed;

    VivecraftRawActionReader(Class<?> actionClass) throws ReflectiveOperationException {
        type = actionClass.getField("type");
        digitalData = actionClass.getField("digitalData");
        analogData = actionClass.getField("analogData");
        Class<?> digitalClass = digitalData.getType().getComponentType();
        Class<?> analogClass = analogData.getType().getComponentType();
        digitalActive = digitalClass.getField("isActive");
        digitalState = digitalClass.getField("state");
        digitalOrigin = digitalClass.getField("activeOrigin");
        analogActive = analogClass.getField("isActive");
        analogX = analogClass.getField("x");
        analogY = analogClass.getField("y");
        analogZ = analogClass.getField("z");
        analogOrigin = analogClass.getField("activeOrigin");
        handed = actionClass.getMethod("isHanded");
    }

    boolean isHanded(Object action) throws ReflectiveOperationException {
        return (boolean) handed.invoke(action);
    }

    boolean anyDown(Object action) throws ReflectiveOperationException {
        if (action == null) return false;
        int count = isHanded(action) ? 2 : 1;
        for (int hand = 0; hand < count; hand++) if (sample(action, hand).down()) return true;
        return false;
    }

    boolean released(Object action) throws ReflectiveOperationException {
        if (action == null) return false;
        int count = isHanded(action) ? 2 : 1;
        for (int hand = 0; hand < count; hand++) {
            Sample sample = sample(action, hand);
            if (!sample.active() || sample.down()) return false;
        }
        return true;
    }

    Sample sample(Object action, int hand) throws ReflectiveOperationException {
        if (action == null || hand < 0 || hand > 1) return Sample.UP;
        String actionType = (String) type.get(action);
        boolean digital = "boolean".equals(actionType);
        int dimensions = switch (actionType) {
            case "vector1" -> 1;
            case "vector2" -> 2;
            case "vector3" -> 3;
            default -> 0;
        };
        if (!digital && dimensions == 0) return Sample.UP;
        Object[] samples = (Object[]) (digital ? digitalData : analogData).get(action);
        if (samples == null || hand >= samples.length || samples[hand] == null) return Sample.UP;
        Object sample = samples[hand];
        if (digital) {
            boolean active = digitalActive.getBoolean(sample);
            return new Sample(active && digitalState.getBoolean(sample), digitalOrigin.getLong(sample), active);
        }
        // Match VRInputAction.isButtonPressed(): an analog axis must exceed 0.5.
        boolean down = axisDown(analogX.getFloat(sample))
                || dimensions >= 2 && axisDown(analogY.getFloat(sample))
                || dimensions >= 3 && axisDown(analogZ.getFloat(sample));
        boolean active = analogActive.getBoolean(sample);
        return new Sample(active && down, analogOrigin.getLong(sample), active);
    }

    private static boolean axisDown(float value) {
        return Float.isFinite(value) && Math.abs(value) > 0.5f;
    }

    record Sample(boolean down, long origin, boolean active) {
        private static final Sample UP = new Sample(false, 0, false);
    }
}
