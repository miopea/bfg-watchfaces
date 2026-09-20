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
        val app: String,
        /** Whether it can fill a `SHORT_TEXT` slot, which every face accepts. */
        val shortText: Boolean = true,
        /** Whether it can fill a `RANGED_VALUE` slot. */
        val ranged: Boolean = false,
        /**
         * What the provider DECLARED, verbatim, for diagnosis.
         *
         * The two booleans above are this string interpreted, and the
         * interpretation is the part that can be wrong -- a type this parser
         * does not recognise reads as absent rather than as unknown. Carrying
         * the raw value means a question about a provider can be answered from
         * a phone in the field instead of from a guess here.
         *
         * Empty when the provider declared nothing at all, which is itself the
         * answer to a different question.
         */
        val declaredTypes: String = ""
    )

    /**
     * Everything installed that one of this app's slots could show.
     *
     * `SHORT_TEXT` or `RANGED_VALUE`, and each provider says which -- the PHONE
     * decides what to offer, because only the phone knows whether the face
     * being edited draws bars. Filtering here would mean the watch deciding on
     * behalf of a face it has never seen.
     *
     * It was SHORT_TEXT alone, which was right while that was the only type any
     * slot declared and wrong as soon as one did not. Measured on the
     * operator's Pixel Watch 5: of ten Fitbit complications, nine publish a
     * value with a range and no short string, so nine of them never reached the
     * picker at all. They did not render badly; they were invisible.
     *
     * A provider that can supply NEITHER is still excluded -- one that only has
     * an image would put a name in the list that silently renders nothing.
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
        val declared = service.metaData?.getString(SUPPORTED_TYPES)
        val shortText = supports(declared, "SHORT_TEXT")
        val ranged = supports(declared, "RANGED_VALUE")
        if (!shortText && !ranged) return null

        val component = "${service.packageName}/${service.name}"
        val label = runCatching { service.loadLabel(pm).toString() }.getOrNull().orEmpty()
        val app = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(service.packageName, 0)).toString()
        }.getOrNull().orEmpty().ifEmpty { service.packageName }

        return Provider(
            component = component,
            // A service's own label is often the app's, and sometimes empty.
            label = label.ifEmpty { app },
            app = app,
            shortText = shortText,
            ranged = ranged,
            declaredTypes = declared.orEmpty()
        )
    }

    /**
     * Whether a provider declares [type].
     *
     * The metadata is a comma-separated list of type names. Absent metadata
     * counts as SHORT_TEXT and nothing else: a provider that does not declare
     * its types is far more likely to be one this parser does not understand
     * than one that supplies nothing, and the face falls back to its system
     * provider anyway if the slot comes back empty. Reading silence as a RANGE
     * would be the opposite bet -- it would put untyped providers in front of
     * people who turned bars on and leave the slot blank.
     */
    private fun supports(declared: String?, type: String): Boolean {
        if (declared.isNullOrBlank()) return type == "SHORT_TEXT"
        return declared.split(",").any { it.trim().equals(type, ignoreCase = true) }
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
            """{"component":${Json.quote(it.component)},"label":${Json.quote(it.label)},"app":${Json.quote(it.app)},"shortText":${it.shortText},"ranged":${it.ranged},"types":${Json.quote(it.declaredTypes)}}"""
        }

}
