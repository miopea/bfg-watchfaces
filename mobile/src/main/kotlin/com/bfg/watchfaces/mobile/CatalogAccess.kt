package com.bfg.watchfaces.mobile

import android.content.Context
import com.bfg.watchfaces.appcore.CatalogService
import java.io.File
import java.util.UUID

/**
 * A thin seat for [CatalogService] on the device, and deliberately nothing more
 * — the same shape as [FaceStorage], for the same reason: the rules live in
 * `:appcore` where both shipped apps and the tests can reach them, and this
 * only supplies what needs a `Context`.
 */
object CatalogAccess {

    /** Cached index lives in the cache directory, which the system may clear. */
    private fun cacheDir(context: Context): File = File(context.cacheDir, "catalog")

    fun service(context: Context): CatalogService =
        CatalogService(cacheDir = cacheDir(context))

    private const val PREFS = "bfg-catalog"
    private const val KEY_INSTALL_ID = "install-id"

    /**
     * The random per-install id.
     *
     * Made once on first use and kept. Sent ONLY when submitting or reporting,
     * never on a read, so browsing stays anonymous.
     *
     * It is deliberately weak, and the app says so at submit rather than
     * letting it be discovered: reinstalling makes a new one, and a face
     * submitted under the old one can then only be withdrawn by reporting it.
     *
     * It is NOT used for moderation. Blocking by it would make it a real
     * identity with consequences while still being defeated by a reinstall —
     * the worst of both. It exists to give an author their own face back.
     */
    fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, fresh).apply()
        return fresh
    }
}
