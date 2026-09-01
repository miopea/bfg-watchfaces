package com.bfg.watchfaces.wear

import com.bfg.watchfaces.appcore.Json
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * Every complication data source installed ON THIS WATCH.
 *
 * ## Why this exists at all
 *
 * Watch Face Format's system provider list has fourteen members and there is no
 * weather in it — verified against Google's own `defaultProviderType`. Weather,
 * Google Health, and everything else people expect are THIRD-PARTY complication
 * data sources, named in a face by ComponentName through `primaryProvider`.
 *
 * A ComponentName cannot be guessed, and it cannot be discovered on the phone:
 * a complication provider is a service on the WATCH. A phone app that shows
 * step counts has nothing to do with it. So the watch has to enumerate them and
 * tell the phone, which is the only reason this file is in `:wear`.
 *
 * ## The action is the legacy one, deliberately
 *
 * Providers register `android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST`,
 * including the ones written against AndroidX — querying the `androidx.` spelling
 * returns nothing. Measured on a Wear OS 6 image: the legacy action found 37
 * services, the AndroidX one found none.
 */
object ProviderCatalog {

    /** What every complication data source registers, AndroidX ones included. */
    private const val ACTION = "android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST"

    /** The metadata a provider uses to say what it can supply. */
    private const val SUPPORTED_TYPES = "android.support.wearable.complications.SUPPORTED_TYPES"

    /** One installed provider, in the terms the phone needs to offer it. */
    data class Provider(
        /** `package/class`, exactly as a face must name it. */
        val component: String,
        /** What to call it in a list, from the app's own label. */
        val label: String,
        /** The app it belongs to, for grouping and for disambiguating labels. */
        val app: String
    )

    /**
     * Everything installed that can fill a SHORT_TEXT slot.
     *
     * Filtered to SHORT_TEXT because that is the only type this face's slots
     * declare. Offering a provider that can only supply an image would put a
     * name in the list that silently renders nothing.
     */
    fun installed(context: Context): List<Provider> {
        val pm = context.packageManager
        val services = runCatching {
            pm.queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA)
        }.getOrElse { return emptyList() }

        return services.mapNotNull { toProvider(pm, it) }
            .distinctBy { it.component }
            .sortedWith(compareBy({ it.app.lowercase() }, { it.label.lowercase() }))
    }

    private fun toProvider(pm: PackageManager, info: ResolveInfo): Provider? {
        val service = info.serviceInfo ?: return null
        if (!supportsShortText(service.metaData?.getString(SUPPORTED_TYPES))) return null

        val component = "${service.packageName}/${service.name}"
        val label = runCatching { service.loadLabel(pm).toString() }.getOrNull().orEmpty()
        val app = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(service.packageName, 0)).toString()
        }.getOrNull().orEmpty().ifEmpty { service.packageName }

        return Provider(
            component = component,
            // A service's own label is often the app's, and sometimes empty.
            label = label.ifEmpty { app },
            app = app
        )
    }

    /**
     * Whether a provider can fill a SHORT_TEXT slot.
     *
     * The metadata is a comma-separated list of type names. Absent metadata is
     * treated as usable rather than excluded: a provider that does not declare
     * its types is far more likely to be one this parser does not understand
     * than one that supplies nothing, and the face falls back to its system
     * provider anyway if the slot comes back empty.
     */
    private fun supportsShortText(declared: String?): Boolean {
        if (declared.isNullOrBlank()) return true
        return declared.split(",").any { it.trim().equals("SHORT_TEXT", ignoreCase = true) }
    }

    /**
     * Every app on the watch that can be opened, for a shortcut slot.
     *
     * A different question from [installed]: that one asks who can FILL a slot
     * with a reading, this asks what pressing one could OPEN. An app can be
     * either, both or neither, so they are two queries rather than one list
     * with a flag.
     *
     * Watch Face Format takes a ComponentName as a `Launch` target, so anything
     * here is a legal target — which is what makes this worth sending.
     */
    fun launchable(context: Context): List<Provider> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching { pm.queryIntentActivities(intent, 0) }
            .getOrElse { return emptyList() }

        return activities.mapNotNull { info ->
            val a = info.activityInfo ?: return@mapNotNull null
            val label = runCatching { a.loadLabel(pm).toString() }.getOrNull().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            Provider(
                component = "${a.packageName}/${a.name}",
                label = label,
                app = label
            )
        }.distinctBy { it.component }.sortedBy { it.label.lowercase() }
    }

    /** The catalog as JSON, for the message the phone asks for. */
    fun toJson(providers: List<Provider>): String =
        providers.joinToString(",", prefix = "[", postfix = "]") {
            """{"component":${Json.quote(it.component)},"label":${Json.quote(it.label)},"app":${Json.quote(it.app)}}"""
        }

}
