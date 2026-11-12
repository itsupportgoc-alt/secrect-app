//! JNI entry points used by `com.secretchat.app.outbox.RustOutbox`.
//!
//! The handle returned by `nativeOpen` is a boxed `Mutex<Outbox>`; every other
//! call locks it, so the Kotlin client can call from any background thread.

use crate::Outbox;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use std::ptr;
use std::sync::Mutex;

struct Handle(Mutex<Outbox>);

fn throw(env: &mut JNIEnv<'_>, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

fn with_outbox<T>(
    env: &mut JNIEnv<'_>,
    handle: jlong,
    action: impl FnOnce(&mut Outbox) -> Result<T, String>,
) -> Option<T> {
    if handle == 0 {
        throw(env, "outbox is closed");
        return None;
    }
    // SAFETY: the pointer comes from `Box::into_raw` in `nativeOpen` and stays
    // valid until `nativeClose`.
    let boxed = unsafe { &*(handle as *const Handle) };
    match boxed.0.lock() {
        Ok(mut outbox) => match action(&mut outbox) {
            Ok(value) => Some(value),
            Err(message) => {
                throw(env, &message);
                None
            }
        },
        Err(_) => {
            throw(env, "outbox lock is poisoned");
            None
        }
    }
}

fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>, label: &str) -> Option<String> {
    match env.get_string(value) {
        Ok(text) => Some(text.into()),
        Err(_) => {
            throw(env, &format!("could not read {label}"));
            None
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_secretchat_app_outbox_RustOutbox_nativeOpen(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    path: JString<'_>,
) -> jlong {
    let Some(path) = read_string(&mut env, &path, "outbox path") else {
        return 0;
    };
    match Outbox::open(path) {
        Ok(outbox) => Box::into_raw(Box::new(Handle(Mutex::new(outbox)))) as jlong,
        Err(err) => {
            throw(&mut env, &err.to_string());
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_secretchat_app_outbox_RustOutbox_nativeClose(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: pointer ownership is returned to the box exactly once.
        unsafe {
            drop(Box::from_raw(handle as *mut Handle));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_secretchat_app_outbox_RustOutbox_nativeEnqueue(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    conversation_id: JString<'_>,
    body: JString<'_>,
) -> jstring {
    let Some(conversation_id) = read_string(&mut env, &conversation_id, "conversation id") else {
        return ptr::null_mut();
    };
    let Some(body) = read_string(&mut env, &body, "body") else {
        return ptr::null_mut();
    };
    let Some(client_id) = with_outbox(&mut env, handle, |outbox| {
        outbox
            .enqueue(&conversation_id, &body)
            .map_err(|err| err.to_string())
    }) else {
        return ptr::null_mut();
    };
    match env.new_string(client_id) {
        Ok(value) => value.into_raw(),
        Err(err) => {
            throw(&mut env, &format!("could not return client id: {err}"));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_secretchat_app_outbox_RustOutbox_nativeDue(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    now_ms: jlong,
) -> jstring {
    let Some(json) = with_outbox(&mut env, handle, |outbox| {
        serde_json::to_string(&outbox.due(now_ms)).map_err(|err| err.to_string())
    }) else {
        return ptr::null_mut();
    };
    match env.new_string(json) {
        Ok(value) => value.into_raw(),
        Err(err) => {
            throw(&mut env, &format!("could not return due messages: {err}"));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_secretchat_app_outbox_RustOutbox_nativePending(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jstring {
    let Some(json) = with_outbox(&mut env, handle, |outbox| {
        serde_json::to_string(&outbox.pending()).map_err(|err| err.to_string())
    }) else {
        return ptr::null_mut();
    };
    match env.new_string(json) {
        Ok(value) => value.into_raw(),
        Err(err) => {
            throw(&mut env, &format!("could not return pending messages: {err}"));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_secretchat_app_outbox_RustOutbox_nativeMarkSent(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    client_id: JString<'_>,
) -> jboolean {
    let Some(client_id) = read_string(&mut env, &client_id, "client id") else {
        return 0;
    };
    match with_outbox(&mut env, handle, |outbox| {
        outbox
            .mark_sent(&client_id)
            .map_err(|err| err.to_string())
    }) {
        Some(removed) => removed as jboolean,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_secretchat_app_outbox_RustOutbox_nativeMarkFailed(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    client_id: JString<'_>,
    now_ms: jlong,
) {
    let Some(client_id) = read_string(&mut env, &client_id, "client id") else {
        return;
    };
    let _ = with_outbox(&mut env, handle, |outbox| {
        outbox
            .mark_failed(&client_id, now_ms)
            .map_err(|err| err.to_string())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_secretchat_app_outbox_RustOutbox_nativePendingCount(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jint {
    match with_outbox(&mut env, handle, |outbox| Ok(outbox.pending_count())) {
        Some(count) => count as jint,
        None => 0,
    }
}
