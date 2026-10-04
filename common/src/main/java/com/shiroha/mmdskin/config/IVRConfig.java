/* 文件职责：定义 VR 运行时可读取的全局配置。 */
package com.shiroha.mmdskin.config;

public interface IVRConfig {
    default boolean isVREnabled() { return false; }

    default float getVRArmIKStrength() { return 1.0f; }
    default boolean isVRModelAboveUi() { return false; }
    default boolean isVRKeyboardEnabled() { return false; }
    default VrKeyboardMode getVRKeyboardMode() { return VrKeyboardMode.AUTO; }
    default boolean isVRKeyboardImeEnabled() { return true; }
    default boolean isVRKeyboardDragSmoothing() { return false; }
    default int getVRKeyboardDragPositionMs() { return 120; }
    default int getVRKeyboardDragRotationMs() { return 160; }
}
