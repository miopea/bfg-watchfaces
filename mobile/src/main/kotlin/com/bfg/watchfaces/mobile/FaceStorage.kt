package com.bfg.watchfaces.mobile

import android.content.Context
import com.bfg.watchfaces.appcore.FaceLibrary
import com.bfg.watchfaces.generator.DialParams

/**
 * The phone's saved faces.
 *
 * A thin seat for [FaceLibrary] on `filesDir`, and deliberately nothing more.
 * Every rule about what a saved face IS — the slug, the JSON, the fact that a
 * saved face is already a catalog submission — lives in `:appcore` and is shared
 * with the workbench. If this file ever grows a second opinion about any of
 * that, the phone starts producing faces the rest of the system cannot read.
 */
object FaceStorage {

    fun list(context: Context): List<FaceLibrary.StoredFace> =
        FaceLibrary.list(context.filesDir)

    fun save(context: Context, name: String, params: DialParams): FaceLibrary.StoredFace =
        FaceLibrary.save(context.filesDir, name, params)

    fun delete(context: Context, slug: String): Boolean =
        FaceLibrary.delete(context.filesDir, slug)

    /**
     * Whether this name would overwrite something already saved.
     *
     * Two names can slug to one file — "Midnight Knot" and "midnight knot" are
     * the same package as far as Watch Face Push is concerned. Better to say so
     * before saving than to silently replace a face somebody made.
     */
    fun existing(context: Context, name: String): FaceLibrary.StoredFace? =
        FaceLibrary.load(context.filesDir, FaceLibrary.slugify(name))
}
