package com.bfg.watchfaces.mobile

import android.content.Context
import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.appcore.Json
import com.bfg.watchfaces.generator.DialParams
import java.io.File

/**
 * The face this phone last put on the watch.
 *
 * ## Why the phone keeps this at all
 *
 * Studio opened on a preset every time, so the first thing anyone saw was a
 * design they were not wearing. The obvious starting point is the face actually
 * on the wrist, and the phone is the only thing that knows it: Watch Face Push
 * has no "what is installed" call this app can make, and the watch cannot be
 * asked while it is out of range.
 *
 * So this is a RECORD OF WHAT WE SENT, not a reading of the watch. It is right
 * whenever this phone is the only thing that has installed faces, which is the
 * whole design, and it goes stale if someone switches face on the watch itself.
 * That is worth saying plainly rather than papering over: the alternative is
 * opening on a preset nobody chose.
 *
 * Written only after a send SUCCEEDS, so a failed transfer does not change what
 * Studio opens on.
 */
object CurrentFace {

    private const val FILE = "current-face.json"

    data class Sent(val name: String, val params: DialParams)

    fun record(context: Context, name: String, params: DialParams) {
        runCatching {
            val body = FaceCodec.toJson(params)
            File(context.filesDir, FILE).writeText(
                """{"name":${Json.quote(name)},"params":$body}"""
            )
        }
    }

    fun load(context: Context): Sent? = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return null
        val o = Json.obj(Json.parse(f.readText()))
        val name = o["name"] as? String ?: return null
        val params = FaceCodec.fromJson(Json.obj(o["params"]))
        Sent(name, params)
    }.getOrNull()

}
