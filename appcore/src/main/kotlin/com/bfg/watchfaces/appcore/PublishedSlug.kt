package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.CatalogContract

/**
 * The slug a face gets when it is PUBLISHED, as opposed to saved locally.
 *
 * ## Why a published slug is not just the name slugified
 *
 * The slug is the `watchfacepush.<slug>` package suffix. Two strangers both
 * calling a face "Midnight" would produce ONE package, and installing the
 * second would silently replace the first on the watch — no error, no warning,
 * just somebody's face gone. That class of invisible difference is the most
 * expensive kind in this project.
 *
 * So a published slug carries a short random id: `midnight_7f3a`,
 * `midnight_c214`. Collisions become impossible by construction rather than by
 * policy, and nobody has to be told their name is taken. The package name is
 * slightly uglier and is visible in Settings on the watch; that is the price,
 * and it was accepted knowingly.
 *
 * **Locally saved faces keep their plain slug.** This applies at the moment a
 * face is published, which is the only moment two strangers' names can meet.
 *
 * ## Why the rule is here
 *
 * Two places need it and they are in different languages. The catalog service
 * CONSTRUCTS the slug when a submission arrives; the moderation pass VERIFIES
 * it before publishing. A third implementation would be a third answer to "what
 * package does this face install as", and disagreeing answers mean faces
 * installing under different packages and silently failing to replace each
 * other.
 *
 * The service builds its half from `params-contract.json`, which is generated
 * from [CatalogContract] — so both halves are downstream of the same numbers.
 */
object PublishedSlug {

    /**
     * The most of a slugified name that survives, leaving room for the id and
     * its separator inside the length limit.
     */
    val STEM_LENGTH: Int = CatalogContract.MAX_SLUG_CHARS - CatalogContract.PUBLISHED_ID_CHARS - 1

    /** What the app sends as the base for [name]; the service appends the id. */
    fun stemFor(name: String): String = FaceLibrary.slugify(name).take(STEM_LENGTH)

    private val ID = Regex("^[0-9a-f]{${CatalogContract.PUBLISHED_ID_CHARS}}$")

    /**
     * Whether [slug] is a published slug for [name].
     *
     * Used by the moderation pass. It deliberately does NOT re-derive the whole
     * slug and compare — the id is random, so there is nothing to compare it
     * to. It checks the two things that can be checked: that the stem is the
     * one this name produces, and that what follows is an id of the right
     * shape.
     *
     * A face whose slug does not describe its name is not necessarily an
     * attack — but it means the package installed on a watch will not match
     * what the gallery calls it, and that is worth a human looking at.
     */
    fun matches(slug: String, name: String): Boolean {
        val separator = slug.lastIndexOf('_')
        if (separator <= 0) return false
        val stem = slug.substring(0, separator)
        val id = slug.substring(separator + 1)
        return stem == stemFor(name) && ID.matches(id)
    }
}
