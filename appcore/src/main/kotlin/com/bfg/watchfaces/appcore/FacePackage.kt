package com.bfg.watchfaces.appcore

/**
 * The package a face installs as, in both directions.
 *
 * ## Why both directions live here
 *
 * Building the name was already a rule, in `FaceBuilder.packageFor`, inside
 * `:mobile`. Reading it back is the same rule run backwards, and writing it out
 * a second time is precisely the mistake `SlotGeometry` exists as a monument
 * to: two copies that agree right up until somebody changes one.
 *
 * The forward direction decides what gets installed on a wrist. The reverse
 * decides which saved design the app believes that installation IS. If they
 * ever disagreed, the app would look at a face it had itself sent and fail to
 * recognise it — and the failure would be silent, because an unrecognised
 * package is indistinguishable from a face somebody else installed.
 *
 * So `:appcore` owns both, next to [FaceLibrary.slugify], which is the rule
 * that makes the slug in the first place.
 *
 * ## The mapping is one-way on NAMES, and that is load-bearing
 *
 * `slugify` is lossy: "Trail Day" becomes `trail_day`, and nothing recovers the
 * capital letters or the space. So a slug read back off a watch identifies a
 * saved face perfectly well — slugs are compared to slugs — and does NOT yield
 * a display name for a face the phone has never heard of. Anything tempted to
 * title-case a slug into a name is inventing something, and the name is the one
 * thing in this app that belongs to the person who chose it.
 */
object FacePackage {

    /**
     * What separates the app's own package from the face's slug.
     *
     * Watch Face Push requires the installed package to sit under the pushing
     * app's package, so every face this app installs is
     * `<app package>.watchfacepush.<slug>`.
     */
    const val MARKER = ".watchfacepush."

    /** The package a face with this [slug] installs as, under [appPackage]. */
    fun nameFor(appPackage: String, slug: String): String = "$appPackage$MARKER$slug"

    /**
     * The slug inside a package name, or null when this is not one of ours.
     *
     * Null is a real answer and not a failure: the watch can be wearing a face
     * from another app entirely, and saying "I do not recognise this" is the
     * honest outcome. It must never be confused with an empty slug.
     *
     * Matched on the LAST occurrence of the marker, because the app package
     * itself is fixed and the slug is what follows it. A slug cannot contain a
     * dot — [FaceLibrary.slugify] emits only lowercase letters, digits and
     * underscores — so everything after the final marker is the slug entire.
     */
    fun slugIn(packageName: String?): String? {
        val p = packageName?.trim().orEmpty()
        val at = p.lastIndexOf(MARKER)
        if (at < 0) return null
        val slug = p.substring(at + MARKER.length)
        return slug.ifEmpty { null }
    }
}
