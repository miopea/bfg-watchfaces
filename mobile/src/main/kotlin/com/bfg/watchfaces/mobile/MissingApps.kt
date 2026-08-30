package com.bfg.watchfaces.mobile

import android.content.Context
import com.bfg.watchfaces.generator.DialParams

/**
 * Apps a face names that this watch does not appear to have.
 *
 * A face can point a slot at a provider app or at an app to open. On a watch
 * without that app, the provider falls back to the slot's system source and a
 * shortcut simply does nothing — so the face renders, and renders DIFFERENTLY
 * from its preview, with nothing anywhere saying why.
 *
 * That silence is the thing worth fixing. Every expensive bug in this project
 * has been a difference nobody could see, and a shared face pointing at an app
 * the recipient lacks is exactly that shape.
 */
object MissingApps {

    /**
     * The names of apps [params] needs and the watch has not reported.
     *
     * Empty when the watch has never sent its catalog, which is the important
     * case: an empty cache means "we do not know", and answering "everything is
     * missing" would put a warning on every face someone owns.
     */
    fun of(context: Context, params: DialParams): List<String> {
        val known = ProviderCache.load(context) + ProviderCache.launchers(context)
        if (known.isEmpty()) return emptyList()

        val byComponent = known.associateBy { it.component }
        val needed = params.providers.values + params.launchers.values
        return needed.distinct()
            .filter { it !in byComponent }
            // No label to show, since the watch has never mentioned it. The
            // package is the honest fallback: it is what the face actually
            // names, and it is usually recognisable.
            .map { it.substringBefore('/') }
    }

    /** One line for a face row, or null when nothing is missing. */
    fun note(context: Context, params: DialParams): String? {
        val missing = of(context, params)
        return when {
            missing.isEmpty() -> null
            missing.size == 1 -> "Uses ${missing.first()}, which isn’t on your watch"
            else -> "Uses ${missing.size} apps that aren’t on your watch"
        }
    }
}
