// A JNI entry point for google/pack, adapted from the wrapper in Google's
// Androidify sample (Apache-2.0, Copyright 2025 The Android Open Source
// Project). The shape is theirs; the symbol name is ours, because the JNI
// symbol encodes the Java package and ours is com.bfg.watchfaces.
//
// Licensed under the Apache License, Version 2.0.

use base64::{engine::general_purpose, Engine};
use jni::{
    objects::{JClass, JObject, JObjectArray, JString},
    sys::jstring,
    JNIEnv,
};
use pack_api::{compile_apk, FileResource, Package};

/// # Safety
/// Called from the JVM. The symbol MUST match Java_<package>_<class>_<method>.
#[no_mangle]
pub unsafe extern "C" fn Java_com_bfg_watchfaces_mobile_pack_PackBridge_nativeCompilePackage(
    mut env: JNIEnv,
    _this: JClass,
    manifest_jstring: JString,
    resources: JObjectArray,
) -> jstring {
    let manifest: String = match env.get_string(&manifest_jstring) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let mut pack_resources = vec![];
    let len = match env.get_array_length(&resources) {
        Ok(n) => n,
        Err(_) => return std::ptr::null_mut(),
    };
    for index in 0..len {
        let resource = match env.get_object_array_element(&resources, index) {
            Ok(r) => r,
            Err(_) => return std::ptr::null_mut(),
        };
        let name = string_field(&mut env, &resource, "name");
        let subdirectory = string_field(&mut env, &resource, "subdirectory");
        let contents_b64 = string_field(&mut env, &resource, "contentsBase64");
        let contents = match general_purpose::STANDARD.decode(contents_b64.as_bytes()) {
            Ok(b) => b,
            Err(_) => return std::ptr::null_mut(),
        };
        pack_resources.push(FileResource::new(subdirectory, name, contents));
    }

    let package = Package {
        android_manifest: manifest.as_bytes().to_vec(),
        resources: pack_resources,
    };

    // A panic here would unwind across the FFI boundary, which is undefined
    // behaviour. Returning null lets the Kotlin side raise something a person
    // can read instead of taking the process down.
    let compiled = match std::panic::catch_unwind(|| compile_apk(&package)) {
        Ok(Ok(bytes)) => bytes,
        _ => return std::ptr::null_mut(),
    };
    match env.new_string(general_purpose::STANDARD.encode(&compiled)) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn string_field(env: &mut JNIEnv, class: &JObject, field: &str) -> String {
    env.get_field(class, field, "Ljava/lang/String;")
        .and_then(|v| v.l())
        .and_then(|o| env.get_string(&o.into()).map(Into::into))
        .unwrap_or_default()
}
