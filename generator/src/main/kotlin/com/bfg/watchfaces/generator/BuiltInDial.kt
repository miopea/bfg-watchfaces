package com.bfg.watchfaces.generator

/**
 * Pictures that ship WITH the app and can be used as a dial.
 *
 * ## Why these are not photographs
 *
 * [Engine.TEXTURE] already draws an image as the dial, and until now the only
 * source was the photo picker. A photo is somebody's own file: it lives on
 * their phone, it never crosses to the catalog, and a face using one is
 * [DialParams.isLocalOnly] and cannot be shared.
 *
 * These are different in the one way that matters. The bytes ship inside the
 * app, so anybody who installs a face using one ALREADY HAS THE IMAGE. There is
 * nothing to upload, nothing of anybody's to leak, and no reason to refuse the
 * share — which makes these the first `TEXTURE` faces that can enter the
 * catalog at all.
 *
 * ## The ids are deliberately not hashes
 *
 * [com.bfg.watchfaces.appcore.TextureStore] ids are exactly 40 lowercase hex
 * characters — a SHA-1 of the imported bytes. These ids contain a hyphen and
 * letters outside `a-f`, so `TextureStore.isId` rejects them by SHAPE. A
 * built-in can never be mistaken for an import, and an import can never
 * masquerade as a built-in, without either check having to know about the
 * other.
 *
 * ## They keep the colour controls working
 *
 * Both marks have a transparent ground, and `drawTexture` composites onto the
 * dial rather than replacing it, so the wearer's dial colour shows behind the
 * mark. A photograph ignores that control; these do not.
 */
enum class BuiltInDial(
    /** Stored in the face, and shared. Never a hash — see the class note. */
    val id: String,
    /** What a person picking one sees. */
    val label: String,
    private val resource: String
) {
    BUGSY("bfg-bugsy", "Bugsy", "/dials/bugsy.png"),
    SWARM_BEE("bfg-bee", "Swarm bee", "/dials/swarm.png");

    /**
     * The PNG bytes, from the jar rather than from either app's resources.
     *
     * One copy, on the classpath of everything that renders: `:mobile` bakes
     * the dial that crosses to the watch, and `:workbench` draws the preview.
     * A second copy would be two pictures that eventually differ.
     */
    fun bytes(): ByteArray? =
        BuiltInDial::class.java.getResourceAsStream(resource)?.use { it.readBytes() }

    companion object {
        fun byId(id: String): BuiltInDial? = entries.firstOrNull { it.id == id }

        /** Every id, for the catalog contract to publish to the Worker. */
        val IDS: List<String> = entries.map { it.id }
    }
}
