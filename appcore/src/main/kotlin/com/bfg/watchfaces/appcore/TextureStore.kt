package com.bfg.watchfaces.appcore

import java.io.File
import java.security.MessageDigest

/**
 * The images a face can use as its dial.
 *
 * ## Why this is in `:appcore` and holds no decoder
 *
 * `DialParams.texture` is an ID, never bytes — the face JSON stays small and
 * portable and the picture lives beside it. That rule is the same on the phone
 * and in the workbench, so it belongs here with the rule about what a face is,
 * exactly as [FaceLibrary] does.
 *
 * What CANNOT live here is decoding. The workbench has `ImageIO` and Android has
 * `BitmapFactory`, and neither exists on the other. So this stores and returns
 * BYTES, and each platform decodes them — the [PatternEngines] arrangement,
 * one definition and two executions.
 *
 * ## Content-addressed, deliberately
 *
 * The id is the SHA-1 of the bytes, so importing the same photo twice costs one
 * file and two faces using it share it. It also means an id cannot be forged
 * into a path: it is hex, checked, and never taken from user text.
 *
 * ## These do not travel
 *
 * A face carrying a texture is [DialParams.isLocalOnly] and cannot reach the
 * catalog, which stores parameters and not bytes. So a texture id is only ever
 * meaningful on the device that imported it, and two devices are not expected
 * to agree about one.
 */
object TextureStore {

    /**
     * Refuse anything absurd before decoding it. A dial is 456px.
     *
     * The check is on the ENCODED size because that is what can be measured
     * without decoding, and decoding is the expensive, attackable step.
     */
    const val MAX_BYTES = 12 * 1024 * 1024

    /** Where the images live under an app's files directory. */
    fun dir(root: File): File = File(root, "textures").apply { mkdirs() }

    /**
     * The id for these bytes: SHA-1, hex, lowercase.
     *
     * Pure, so the workbench and the phone name the same photo the same way even
     * though nothing requires them to.
     */
    fun idFor(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * True for a string that could be one of our ids.
     *
     * A texture id reaches [file] and becomes a PATH, and it arrives from a
     * stored face — which on the catalog side is somebody else's JSON. Hex only,
     * so "../../etc/passwd" is rejected by shape rather than by sanitising,
     * which is the check that cannot be got round.
     */
    fun isId(id: String): Boolean = id.length == 40 && id.all { it in '0'..'9' || it in 'a'..'f' }

    fun file(root: File, id: String): File? =
        if (!isId(id)) null else File(dir(root), "$id.png")

    /**
     * Store [bytes] and return the id.
     *
     * The caller has already decoded and re-encoded to PNG — that is the
     * platform's job, and doing it proves the bytes really are an image before
     * they can reach a renderer, as well as stripping whatever metadata the
     * original carried. A photo's EXIF holds a location.
     */
    fun save(root: File, bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "empty image" }
        require(bytes.size <= MAX_BYTES) {
            "image is ${bytes.size / 1024}KB; the limit is ${MAX_BYTES / 1024 / 1024}MB"
        }
        val id = idFor(bytes)
        val f = File(dir(root), "$id.png")
        // Content-addressed, so an identical import is already here.
        if (!f.isFile) f.writeBytes(bytes)
        return id
    }

    /** The stored bytes, or null when the id is unknown or malformed. */
    fun load(root: File, id: String): ByteArray? =
        file(root, id)?.takeIf { it.isFile }?.readBytes()

    fun has(root: File, id: String): Boolean = file(root, id)?.isFile == true

    /** Forget an image. Returns false when it was not there. */
    fun delete(root: File, id: String): Boolean =
        file(root, id)?.let { if (it.isFile) it.delete() else false } ?: false
}
