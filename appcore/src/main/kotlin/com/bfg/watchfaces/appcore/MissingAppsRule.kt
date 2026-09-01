package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.DialParams

/**
 * Which apps a face names that a watch has not reported.
 *
 * ## Why this is here and not in the phone app
 *
 * It was in `:mobile`, where nothing can test it — and `MissingAppsRuleTest`
 * "covered" it by declaring its own private copy of the filter and testing
 * that. So the function that decides what warning a person sees on every face
 * row had no test touching it at all, while a test named after it passed.
 *
 * Two implementations agreeing until they don't is the failure this project
 * keeps recording: `SlotGeometry` exists because slot boxes were computed twice
 * and matched while overlapping; the complication labels and samples were
 * duplicated and had already drifted. A copy inside the test is the same shape
 * with the drift hidden better, because the copy is the thing being asserted.
 *
 * The pure rule lives here. `:mobile` keeps only the part that needs a
 * `Context` — reading the cache off disk.
 */
object MissingAppsRule {

    /**
     * Names of apps [params] needs that are not in [known].
     *
     * [known] is every component the watch has reported, providers and
     * launchable apps together. **Empty means "we do not know", not "nothing is
     * installed"** — a watch that has never sent its catalog must not produce a
     * warning on every face somebody owns, so an empty [known] yields nothing.
     * That distinction is the whole reason this returns a list rather than a
     * boolean.
     */
    fun namesOf(params: DialParams, known: Set<String>): List<String> {
        if (known.isEmpty()) return emptyList()
        return (params.providers.values + params.launchers.values)
            .distinct()
            .filter { it !in known }
            // No label to show, since the watch has never mentioned it. The
            // package is the honest fallback: it is what the face actually
            // names, and it is usually recognisable.
            .map { it.substringBefore('/') }
    }

    /**
     * One line for a face row, or null when nothing is missing.
     *
     * Names the app when there is one and counts them when there are several:
     * a row is one line, and three package names in it is not a sentence
     * anybody reads.
     */
    fun note(missing: List<String>): String? = when {
        missing.isEmpty() -> null
        missing.size == 1 -> "Uses ${missing.first()}, which isn’t on your watch"
        else -> "Uses ${missing.size} apps that aren’t on your watch"
    }
}
