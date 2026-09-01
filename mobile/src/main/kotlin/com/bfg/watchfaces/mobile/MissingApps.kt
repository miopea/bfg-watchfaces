package com.bfg.watchfaces.mobile

import android.content.Context
import com.bfg.watchfaces.appcore.MissingAppsRule
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
    /**
     * Everything the watch has told us about, providers and launchable apps.
     *
     * The only part of this that needs a `Context`. The RULE — which of a
     * face's apps are missing, and what to say about it — is
     * [MissingAppsRule], in `:appcore`, where it can be tested without an
     * emulator.
     */
    fun known(context: Context): Set<String> =
        (ProviderCache.load(context) + ProviderCache.launchers(context))
            .map { it.component }
            .toSet()

    /** One line for a face row, or null. [known] is read once by the caller. */
    fun note(params: DialParams, known: Set<String>): String? =
        MissingAppsRule.note(MissingAppsRule.namesOf(params, known))
}
