package com.bfg.watchfaces.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.bfg.watchfaces.appcore.TextureStore
import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import java.io.ByteArrayOutputStream

/**
 * The phone's side of an imported image: choosing one, and finding it again.
 *
 * [TextureStore] in `:appcore` owns what an id IS and where a file goes.
 * Everything here is the part that cannot live there — decoding, downscaling and
 * reading a `content://` URI, none of which exist off Android.
 *
 * ## The photo never leaves the phone
 *
 * A face carrying one is [DialParams.isLocalOnly], and the catalog stores
 * parameters rather than bytes, so there is no path by which it could. That is
 * why the Data Safety declaration can say photos are not collected and mean it.
 */
object Textures {

    private const val TAG = "BfgTextures"

    /**
     * Everything past this is thrown away on import.
     *
     * The dial is 456px. A modern phone photo is twelve megapixels, which is
     * slow to decode, large to keep, and identical on a watch once it has been
     * cropped to a circle the size of a coin. Storing the big one would cost
     * space and time to produce a pixel-for-pixel identical face.
     *
     * Twice the dial rather than exactly the dial, so the cover-crop still has
     * something to work with on an image whose aspect ratio is far from square.
     */
    private const val MAX_EDGE = DIAL_SIZE * 2

    /**
     * What `contrast` becomes when a photo first arrives.
     *
     * `contrast` means "how much of the image survives": 100 is the photo
     * untouched, lower fades it toward the dial colour. The default of 30 was
     * chosen for a guilloche PATTERN, which is meant to be seen — a photo is
     * meant to be underneath, and the two do not want the same number.
     *
     * Applied only on ARRIVAL. Swapping one photo for another keeps whatever
     * was tuned for the last one, because at that point it is a value somebody
     * chose and this has no business overwriting it.
     */
    const val ARRIVING_CONTRAST = 18.0

    /**
     * Import the image at [uri] and return its id, or null if it cannot be read.
     *
     * Re-encoded to PNG rather than stored as it arrived. That proves the bytes
     * really are an image before they can reach a renderer, and it strips the
     * metadata the original carried — **a photo's EXIF holds the location it was
     * taken**, and there is no reason for that to survive into an app's storage.
     */
    fun import(context: Context, uri: Uri): String? = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val decoded = decodeScaled(raw) ?: return null
        val png = ByteArrayOutputStream().use { out ->
            decoded.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        decoded.recycle()
        TextureStore.save(context.filesDir, png)
    }.getOrElse {
        // Never a crash: the picker can hand back something unreadable, and the
        // honest answer is that this image did not work, not that the app did.
        Log.w(TAG, "could not import the chosen image", it)
        null
    }

    /**
     * The image this face uses, or null for every other engine.
     *
     * Null is also the answer when the file is gone. A face can outlive its
     * image — which is why the bytes are COPIED at import rather than referenced
     * by URI — and if it somehow happens the dial falls back to plain rather
     * than failing, which is what `DialParams.texture` documents.
     */
    fun forFace(context: Context, p: DialParams): Bitmap? {
        if (p.engine != Engine.TEXTURE || p.texture.isBlank()) return null
        val bytes = TextureStore.load(context.filesDir, p.texture) ?: return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrElse {
                Log.w(TAG, "stored texture ${p.texture} would not decode", it)
                null
            }
    }

    /**
     * Decode at no more than [MAX_EDGE], using the sampling the decoder offers.
     *
     * `inSampleSize` halves during decode rather than after, so a large photo
     * never has to exist at full size in memory — which is the difference
     * between importing a 12MP image and running out of heap on a mid-range
     * phone holding a watch face preview at the same time.
     */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE && bounds.outHeight / sample > MAX_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
