package com.bfg.watchfaces.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opening the phone's Wear OS companion app.
 *
 * Everything a wearer does to a face AFTER it is installed happens there or on
 * the watch: choosing which complication provider fills a slot, and picking the
 * face itself. This app cannot do any of it — Watch Face Push installs a face
 * and has no API to configure one — so "go and open that app" is a real step in
 * the flow, and it had no link.
 *
 * Two companions exist and which one a phone has depends on the watch. The
 * Pixel Watch app is the one on this project's hardware; the older Wear OS app
 * is still current for other makes. Try each, then fall back to Play.
 */
object WatchCompanion {

    /** The Pixel Watch app, then the general Wear OS app. */
    private val PACKAGES = listOf(
        "com.google.android.apps.wear.companion",
        "com.google.android.wearable.app"
    )

    /** The one that is actually installed, or null. */
    fun installed(context: Context): String? =
        PACKAGES.firstOrNull { pkg ->
            runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() != null
        }

    /**
     * Open it, or send the person to its Play listing if it is missing.
     *
     * Returns false only when neither could be started, so the caller can say
     * something rather than appear to do nothing.
     */
    fun open(context: Context): Boolean {
        val pkg = installed(context)
        if (pkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(intent) }.isSuccess
        }
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${PACKAGES[0]}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(store) }.isSuccess) return true
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=${PACKAGES[0]}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(web) }.isSuccess
    }
}
