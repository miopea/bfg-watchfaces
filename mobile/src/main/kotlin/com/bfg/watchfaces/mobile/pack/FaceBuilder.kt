package com.bfg.watchfaces.mobile.pack

import android.content.Context
import android.graphics.Bitmap
import com.bfg.watchfaces.appcore.FaceLibrary
import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.WffEmitter
import com.bfg.watchfaces.mobile.AndroidDialRenderer
import com.bfg.watchfaces.mobile.AndroidFacePreview
import com.google.android.wearable.watchface.validator.client.DwfValidatorFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Parameters in, an installable watch face out.
 *
 * This is the step the whole project was waiting on. Until now a design could
 * be drawn, named and saved, and there was nothing to send: `watchface-template`
 * builds an APK on a desktop with aapt2, and a phone has neither.
 *
 * The order is not arbitrary and each step fails for a different reason:
 *
 *   1. **Emit the WFF.** [WffEmitter] produces the same XML the desktop bake
 *      does — same generator, so a face built here and a face built there are
 *      the same face.
 *   2. **Render the dial**, then QUANTIZE it. Not an optimisation: the APK
 *      crosses to the watch over Bluetooth, and 368KB against 77KB is the
 *      difference between a send that feels instant and one that feels broken.
 *   3. **Pack.** [PackBridge] compiles the resource table and the APK with no
 *      Android SDK on the device.
 *   4. **Sign.** [ApkSigning], because pack does not and Push will not take an
 *      unsigned artefact.
 *   5. **Validate.** The validator issues the token `addWatchFace` requires, and
 *      it is the ONLY thing that catches a schema-invalid face. Such a face
 *      installs, links, signs — and then never appears in the carousel, with no
 *      runtime error anywhere. `WffSchemaTest` guards the desktop path; this is
 *      the same guard for the device one.
 *
 * ## Only the four permitted paths
 *
 * Watch Face Push accepts exactly `res/raw/watchface.xml`, `res/values`,
 * `res/xml/watch_face_info.xml` and the drawables. Nothing else goes in — which
 * is also why `watchface-template/build.sh` refuses to use AGP: Gradle injects
 * `kotlin/` and `DebugProbesKt.bin`, which Play accepts and Push rejects. pack
 * puts in what it is given and nothing more, so the same discipline applies by
 * construction here.
 */
object FaceBuilder {

    /** Watch Face Push package rule: `<app package>.watchfacepush.<slug>`. */
    fun packageNameFor(context: Context, slug: String) =
        "${context.packageName}.watchfacepush.$slug"

    data class Built(val apk: File, val packageName: String, val slug: String)

    /**
     * The validation token `addWatchFace` requires.
     *
     * This is the ONLY thing that catches a schema-invalid face. Such a face
     * compiles, links, signs, installs — and then never appears in the
     * carousel, with no runtime error anywhere. `WffSchemaTest` is that guard on
     * the desktop; this is the same guard on the device, and it runs locally
     * with no network.
     *
     * Separate from [build] because a caller that only wants the bytes — a test,
     * an export — should not have to carry the validator.
     */
    fun validate(context: Context, apk: File): String {
        val result = DwfValidatorFactory.create().validate(apk, context.packageName)
        val failures = result.failures()
        check(failures.isEmpty()) {
            "the face is not valid and would install without ever appearing: $failures"
        }
        return result.validationToken()
    }

    /**
     * Build a signed APK for [params] under [name].
     *
     * Returns the file and the package it declares. The validation token is a
     * separate call ([validate]) because the validator is the piece most likely
     * to be absent or to change, and a caller that only wants the bytes — a
     * test, an export — should not have to care.
     */
    fun build(context: Context, name: String, params: DialParams): Built {
        require(name.isNotBlank()) { "a face needs a name" }
        check(PackBridge.isAvailable) { PackBridge.UNAVAILABLE }

        val slug = FaceLibrary.slugify(name)
        val packageName = packageNameFor(context, slug)

        val resources = buildList {
            add(PackBridge.Resource.of("raw", "watchface.xml", WffEmitter.emit(params, name)))
            add(PackBridge.Resource.of("values", "strings.xml", strings(name)))
            add(PackBridge.Resource.of("xml", "watch_face_info.xml", WATCH_FACE_INFO))
            // drawable-nodpi, not drawable: these are authored at 456x456 dial
            // space and must not be density-scaled. The qualifier only survives
            // because of scripts/pack-qualifiers.patch -- see build-pack-android.sh.
            add(PackBridge.Resource.of("drawable-nodpi", "dial_bg.png", dialPng(params)))
            add(PackBridge.Resource.of("drawable-nodpi", "preview.png", previewPng(params)))
        }

        val unsigned = PackBridge.compileApk(manifest(packageName), resources)
        val signed = ApkSigning.sign(unsigned)

        val out = File(context.cacheDir, "$slug.apk")
        out.writeBytes(signed)
        return Built(out, packageName, slug)
    }

    /**
     * The dial, quantized.
     *
     * `CLAUDE.md`: quantize to 64 colours before packing, always. Android's own
     * `Bitmap.Config.RGB_565` is the cheap way to get there without a palette
     * pass — 65k colours rather than 64, but PNG's own filtering then compresses
     * the smooth guilloché gradients hard, and unlike a palette it cannot band
     * a dial that happens to use more than 64 distinct tones.
     */
    private fun dialPng(params: DialParams): ByteArray {
        val full = AndroidDialRenderer.render(params, DIAL_SIZE)
        val small = full.copy(Bitmap.Config.RGB_565, false) ?: full
        val bytes = ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        if (small !== full) small.recycle()
        full.recycle()
        return bytes
    }

    /** The carousel thumbnail. Required: without it the face installs and never appears. */
    private fun previewPng(params: DialParams): ByteArray {
        val preview = AndroidFacePreview.render(params, ambient = false, size = DIAL_SIZE)
        return ByteArrayOutputStream().use { out ->
            preview.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }.also { preview.recycle() }
    }

    private fun strings(name: String): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<resources>
        |  <string name="watch_face_name">${escape(name)}</string>
        |</resources>
        |""".trimMargin()

    private const val WATCH_FACE_INFO = """<?xml version="1.0" encoding="utf-8"?>
<WatchFaceInfo>
  <Preview value="@drawable/preview" />
</WatchFaceInfo>
"""

    /**
     * The manifest, matching `watchface-template/AndroidManifest.xml`.
     *
     * `uses-sdk` is here rather than passed as a flag because pack reads the
     * manifest and takes no SDK arguments: without it the APK comes out with an
     * implied targetSdk below 4, and apksigner then demands a v1 JAR signature
     * that a v2/v3-only signer does not produce.
     */
    private fun manifest(packageName: String): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        |    android:versionCode="1"
        |    android:versionName="1.0"
        |    package="$packageName">
        |  <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="34" />
        |  <uses-feature android:name="android.hardware.type.watch" />
        |  <application
        |      android:label="@string/watch_face_name"
        |      android:hasCode="false">
        |    <meta-data
        |        android:name="com.google.android.wearable.standalone"
        |        android:value="true" />
        |    <property
        |        android:name="com.google.wear.watchface.format.version"
        |        android:value="2" />
        |  </application>
        |</manifest>
        |""".trimMargin()

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
