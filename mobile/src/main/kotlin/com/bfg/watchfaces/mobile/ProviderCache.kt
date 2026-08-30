package com.bfg.watchfaces.mobile

import android.content.Context
import com.bfg.watchfaces.appcore.Json
import java.io.File

/**
 * The complication providers installed on the watch, as last reported.
 *
 * ## Why the phone keeps a copy
 *
 * A complication provider is a service on the WATCH. A phone app that shows
 * step counts has nothing to do with it, so the phone cannot enumerate them and
 * its picker could only ever offer what this build happens to know — which is
 * why weather, Google Health and everything else were missing from the list.
 *
 * The watch sends its catalog back on a successful send, and this holds it. So
 * the list is exactly as fresh as the last send: an app installed since will
 * not appear until the next one. That is the accepted cost of a picker that
 * still works with the watch charging in another room, and it beats a picker
 * that needs Bluetooth to open.
 */
object ProviderCache {

    private const val FILE = "watch-providers.json"

    /** One provider, in the terms the picker needs. */
    data class Provider(val component: String, val label: String, val app: String)

    fun save(context: Context, json: String) {
        runCatching { File(context.filesDir, FILE).writeText(json) }
    }

    /** What the watch last reported, or empty when it has never said. */
    fun load(context: Context): List<Provider> = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return emptyList()
        Json.arr(Json.parse(f.readText())).mapNotNull { entry ->
            val o = Json.obj(entry)
            val component = o["component"] as? String ?: return@mapNotNull null
            Provider(
                component = component,
                label = o["label"] as? String ?: component,
                app = o["app"] as? String ?: ""
            )
        }
    }.getOrElse { emptyList() }

    /** The label for a component, when a saved face names one we know. */
    fun labelFor(context: Context, component: String): String? =
        load(context).firstOrNull { it.component == component }?.label
}
