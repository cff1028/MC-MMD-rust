//! The VR keyboard is deliberately separate from model handles and accepts only text-session commands.
use crate::keyboard;
use jni::{
    objects::{JClass, JString},
    sys::{jboolean, jint, jlong, jstring},
    JNIEnv,
};
fn guard<T: Default>(operation: impl FnOnce() -> T) -> T {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(operation)).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_compat_vr_keyboard_VrKeyboardNative_nativeOpen(
    _env: JNIEnv,
    _class: JClass,
    owner: jlong,
) -> jboolean {
    guard(|| keyboard::open(owner) as jboolean)
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_compat_vr_keyboard_VrKeyboardNative_nativeClose(
    _env: JNIEnv,
    _class: JClass,
) {
    guard(keyboard::close);
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_compat_vr_keyboard_VrKeyboardNative_nativeCommand(
    _env: JNIEnv,
    _class: JClass,
    kind: jint,
    value: jint,
    shift: jboolean,
) -> jboolean {
    guard(|| keyboard::command(kind, value, shift != 0) as jboolean)
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_compat_vr_keyboard_VrKeyboardNative_nativeText(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jboolean {
    let Ok(text) = env.get_string(&text) else {
        return 0;
    };
    guard(|| keyboard::text(text.into()) as jboolean)
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_compat_vr_keyboard_VrKeyboardNative_nativePoll(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(guard(|| keyboard::poll().json()))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
