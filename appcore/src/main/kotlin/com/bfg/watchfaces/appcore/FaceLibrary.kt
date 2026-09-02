package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.CURRENT_GENERATOR_VERSION
import com.bfg.watchfaces.generator.DialParams
import java.io.File
import java.time.Instant

/**
 * Saved faces, on disk, as `faces/<slug>.json`.
 *
 * In :appcore because the PHONE saves faces too, into its own app storage, and
 * a face saved there has to be the same file a workbench save produces -- same
 * slug rule, same JSON. The slug is the Watch Face Push package suffix, so a
 * second implementation that disagreed would produce faces that install under a
 * different package and silently stop replacing each other on the watch.
 *
 * This is deliberately the shape docs/SPEC.md describes for the community
 * catalog: one JSON file per face, parameters only, no rasters. A face saved in
 * the app is already a catalog submission -- there is no separate export step to
 * build later, and no second format to keep in sync with this one.
 *
 * Parameters only is also the IP shield: you cannot encode someone's logo as
 * "knotwork engine, scale 26, pewter". Imported photos, if they ever exist,
 * stay local and never become one of these files.
 */
object FaceLibrary {

    data class StoredFace(
        val slug: String,
        val name: String,
        val created: String,
        val params: DialParams
    )

    fun dir(root: File): File = File(root, "faces").apply { mkdirs() }

    /**
     * Slugs are the Watch Face Push package suffix, so the rules are theirs, not
     * ours: `<app package>.watchfacepush.<slug>` and Push rejects anything that
     * is not lowercase alphanumeric/underscore starting with a letter.
     */
    fun slugify(name: String): String {
        // ASCII only, deliberately. Char.isLetterOrDigit() is true for 'é' and
        // for most of Unicode, so a name like "Café Crème" would sail through
        // here and be REJECTED by Watch Face Push at install time, which is far
        // too late to find out. Push wants ^[a-z][a-z0-9_]*$ and nothing else.
        val s = name.trim().lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
        // A package segment cannot start with a digit either.
        val safe = if (s.isEmpty() || s.first() !in 'a'..'z') "face_$s".trim('_') else s
        return safe.take(40).trim('_')
    }

    /**
     * Saving is what moves a face to the CURRENT generator version.
     *
     * A stored face keeps the version it was written with, so its geometry
     * never changes under somebody — which is right for a face you installed
     * from the gallery, and wrong for your own.
     *
     * It bit exactly that way. v11 widened the top complication box because a
     * long weather value was being clipped, and the fix could not reach the
     * face the operator was actually wearing: it had been saved at v10 and
     * every send rebuilt it at v10, faithfully reproducing the bug. There was
     * no way forward short of building the design again from scratch.
     *
     * So the rule is: saving is a deliberate act on your own design, and it
     * takes the current version with it. Reading, sending and installing all
     * leave the stored version alone. That keeps the guarantee where it earns
     * its keep — a face you did not make does not change on its own — without
     * freezing people out of fixes to their own work.
     */
    fun save(root: File, name: String, params: DialParams): StoredFace {
        require(name.isNotBlank()) { "a face needs a name" }
        val slug = slugify(name)
        val current = params.copy(generatorVersion = CURRENT_GENERATOR_VERSION)
        val face = StoredFace(slug, name.trim(), Instant.now().toString(), current)
        File(dir(root), "$slug.json").writeText(toJson(face))
        return face
    }

    fun list(root: File): List<StoredFace> =
        (dir(root).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { fromJson(f.readText()) }.getOrNull() }
            .sortedByDescending { it.created }

    fun load(root: File, slug: String): StoredFace? {
        val f = File(dir(root), "${slugify(slug)}.json")
        return if (f.isFile) runCatching { fromJson(f.readText()) }.getOrNull() else null
    }

    fun delete(root: File, slug: String): Boolean =
        File(dir(root), "${slugify(slug)}.json").let { if (it.isFile) it.delete() else false }

    fun toJson(f: StoredFace): String = """{
  "name": ${Json.quote(f.name)},
  "slug": ${Json.quote(f.slug)},
  "created": ${Json.quote(f.created)},
  "params": ${FaceCodec.toJson(f.params).prependIndent("  ").trimStart()}
}
"""

    fun fromJson(text: String): StoredFace {
        val root = Json.obj(Json.parse(text))
        val params = FaceCodec.fromJson(Json.obj(root["params"]))
        val name = Json.str(root, "name", "Untitled")
        return StoredFace(
            slug = Json.str(root, "slug", slugify(name)),
            name = name,
            created = Json.str(root, "created", ""),
            params = params
        )
    }
}
