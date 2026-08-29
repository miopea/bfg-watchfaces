package com.bfg.watchfaces.mobile.pack

import android.util.Base64

/**
 * The JNI seam onto google/pack, which compiles an APK with no Android SDK.
 *
 * ## The symbol name is the reason this class is shaped like this
 *
 * JNI resolves `nativeCompilePackage` to the C symbol
 * `Java_com_bfg_watchfaces_mobile_pack_PackBridge_nativeCompilePackage`. The
 * package and class name are therefore part of the ABI: rename either and the
 * library stops resolving, with an `UnsatisfiedLinkError` that names the method
 * and not the reason.
 *
 * It also means a prebuilt `.so` cannot be dropped in from elsewhere. Google's
 * Androidify sample ships one, but its symbol carries
 * `com_android_developers_androidify_watchface_creator_PackPackage`, so using it
 * would force this binding into their package. `scripts/build-pack-android.sh`
 * builds ours instead — from the same pinned, PATCHED pack the desktop CLI uses,
 * which matters for more than tidiness: Androidify's binary is built from
 * unpatched pack, where `res/drawable-nodpi` is recorded as mdpi and the watch
 * scales a dial that says do not scale me.
 *
 * ## Absent is a normal state, not a crash
 *
 * The `.so` is build output and gitignored. A checkout that has not run the
 * build script has no library, and [isAvailable] is how every caller finds that
 * out — rather than the app dying at class-load time on a device where nobody
 * could have known.
 */
object PackBridge {

    /** A file destined for `res/<subdirectory>/<name>` in the built APK. */
    data class Resource(
        @JvmField val subdirectory: String,
        @JvmField val name: String,
        @JvmField val contentsBase64: String
    ) {
        companion object {
            fun of(subdirectory: String, name: String, bytes: ByteArray) =
                Resource(subdirectory, name, Base64.encodeToString(bytes, Base64.NO_WRAP))

            fun of(subdirectory: String, name: String, text: String) =
                of(subdirectory, name, text.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * True when the native library loaded.
     *
     * Deliberately not a thrown exception on first use: "pack is not built into
     * this APK" is a buildable-artefact question, and the UI needs to be able to
     * ask it before offering an action it cannot complete.
     */
    val isAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("pack_java") }.isSuccess
    }

    /** The unavailable reason, in words, for a screen to show. */
    const val UNAVAILABLE =
        "This build cannot pack a watch face: the pack library is missing. " +
            "Run scripts/build-pack-android.sh and rebuild."

    /**
     * Compile [manifest] and [resources] into an UNSIGNED APK.
     *
     * Signing is a separate step ([ApkSigning]) because pack does not do it and
     * Watch Face Push will not take an unsigned artefact.
     */
    fun compileApk(manifest: String, resources: List<Resource>): ByteArray {
        check(isAvailable) { UNAVAILABLE }
        val base64 = nativeCompilePackage(manifest, resources.toTypedArray())
            ?: error("pack could not compile the package")
        return Base64.decode(base64, Base64.DEFAULT)
    }

    // Returns null rather than throwing across the FFI boundary: a Rust panic
    // unwinding into the JVM is undefined behaviour, so the wrapper catches it
    // and hands back null for this side to turn into something readable.
    @JvmStatic
    private external fun nativeCompilePackage(
        androidManifest: String,
        resources: Array<Resource>
    ): String?
}
