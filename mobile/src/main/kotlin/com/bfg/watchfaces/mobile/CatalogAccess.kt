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

    // The random per-install id is GONE. It existed only so an author could
    // withdraw their own face, and it was deliberately weak -- a reinstall made
    // a new one and orphaned the old face. A Google sign-in does that job
    // properly, and only when somebody publishes: browsing and reporting carry
    // no identity at all. See DECISIONS.md 2026-08-31.
}
