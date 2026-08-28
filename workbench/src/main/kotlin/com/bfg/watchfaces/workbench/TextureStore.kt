package com.bfg.watchfaces.workbench

import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Images people import for [com.bfg.watchfaces.generator.Engine.TEXTURE].
 *
 * Stored under `textures/<sha1>.png`, content-addressed so importing the same
 * picture twice is free and a face's reference can never point at different
 * bytes later.
 *
 * These deliberately live OUTSIDE the face format. docs/SPEC.md's catalog is
 * parametric-only, for two reasons that both still hold: a face has to stay a
 * few KB of JSON, and parameters are the IP shield -- you cannot encode a
 * copyrighted logo as "knotwork, scale 26, pewter", but you can certainly
 * upload one. The SPEC carves out exactly this: photos are imported locally and
 * never enter the shared catalog. Nothing here is ever published by the app.
 */
object TextureStore {

    /** Refuse anything absurd before decoding it. A dial is 456px. */
    const val MAX_BYTES = 12 * 1024 * 1024

    data class Texture(val id: String, val width: Int, val height: Int, val bytes: Long)

    fun dir(root: File): File = File(root, "textures").apply { mkdirs() }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Decode, normalize to PNG, store by content hash.
     *
     * Re-encoding rather than storing the upload verbatim is deliberate: it
     * proves the bytes actually decode as an image before they can reach the
     * renderer, and it strips whatever metadata the original carried.
     */
    fun save(root: File, bytes: ByteArray): Texture {
        require(bytes.isNotEmpty()) { "empty upload" }
        require(bytes.size <= MAX_BYTES) { "image is ${bytes.size / 1024}KB; limit is ${MAX_BYTES / 1024 / 1024}MB" }
        val img = ImageIO.read(bytes.inputStream())
            ?: throw IllegalArgumentException("not a readable image (PNG, JPEG, GIF or BMP)")

        val id = sha1(bytes)
        val f = File(dir(root), "$id.png")
        if (!f.isFile) ImageIO.write(toArgb(img), "png", f)
        return Texture(id, img.width, img.height, f.length())
    }

    private fun toArgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_ARGB) return src
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return out
    }

    fun load(root: File, id: String): BufferedImage? {
        if (!id.matches(Regex("^[0-9a-f]{40}$"))) return null   // also blocks path traversal
        val f = File(dir(root), "$id.png")
        return if (f.isFile) runCatching { ImageIO.read(f) }.getOrNull() else null
    }

    fun list(root: File): List<Texture> =
        (dir(root).listFiles { f -> f.extension == "png" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return@mapNotNull null
                Texture(f.nameWithoutExtension, img.width, img.height, f.length())
            }

    fun delete(root: File, id: String): Boolean {
        if (!id.matches(Regex("^[0-9a-f]{40}$"))) return false
        val f = File(dir(root), "$id.png")
        return f.isFile && f.delete()
    }

    /**
     * Is this image big enough to fill a 456px dial without upscaling?
     *
     * The dial is 456x456 and the workbench previews at 2x, so the honest bar is
     * the SHORT edge, since the image is centre-cropped to a square.
     */
    fun qualityNote(t: Texture): String {
        val short = minOf(t.width, t.height)
        return when {
            short >= 912 -> "${t.width}x${t.height} — plenty for a 456px dial"
            short >= 456 -> "${t.width}x${t.height} — enough at 1x, will soften on a hi-dpi preview"
            else -> "${t.width}x${t.height} — below 456px, it will be upscaled and look soft"
        }
    }
}
