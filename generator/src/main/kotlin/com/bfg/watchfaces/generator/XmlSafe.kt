package com.bfg.watchfaces.generator

/**
 * Putting a string into XML without breaking the document.
 *
 * ## Why this exists here rather than being validated upstream
 *
 * [WffEmitter] builds XML by string interpolation, and several of the values it
 * interpolates now come from strangers: a catalog submission chooses its own
 * name, font family and provider components. The question raised was whether to
 * escape here or to rely on the catalog service refusing bad values at its
 * boundary.
 *
 * It is both, and they are not the same job:
 *
 * - **Validation is policy.** "Is `Roboto Flex` a legal font family?" has one
 *   right answer and must have exactly one home, which is the generated
 *   contract. Two homes is the mistake `SlotGeometry`, `ControlInventory`,
 *   `EngravedStroke` and `PublishedSlug` were each created to undo.
 * - **Escaping is encoding.** "How do I write an arbitrary string into an XML
 *   attribute?" is not a question about watch faces at all. It belongs to
 *   whatever writes the XML, and its answer does not change when the policy
 *   does.
 *
 * Keeping both is not a fifth duplication. It is the difference between knowing
 * a value is acceptable and knowing the file is well-formed.
 *
 * ## What settled it was a bug that has nothing to do with the catalog
 *
 * A face named `Rock -- Roll` does not build. Today, locally, for anybody:
 *
 * ```text
 * The string "--" is not permitted within comments.
 * ```
 *
 * The name is interpolated into the header comment, and it never passes through
 * [DialParams] — it is a separate argument to [WffEmitter.emit] — so there was
 * no upstream seam that could have caught it even in principle. An answer of
 * "validate at the boundary" would have left that bug in place, because the
 * boundary in question is the catalog and this breaks for someone who never
 * touches the catalog.
 *
 * ## Comments cannot be escaped, only sanitized
 *
 * XML defines no escape mechanism inside a comment: entities are not expanded
 * there, so `&#45;&#45;` is literally `&#45;&#45;`. `--` is simply illegal, and
 * a comment may not end with `-`. The only options are to change the text or to
 * leave it out. [comment] changes it, and says so, because the alternative is
 * dropping the one line that tells someone opening the file which face it is.
 */
object XmlSafe {

    /**
     * A value going inside `attr="..."`.
     *
     * Escapes both quote styles even though the emitter only ever uses double
     * quotes. A helper that is safe only for the caller that exists today is a
     * trap for the one written next year.
     */
    fun attr(s: String): String = buildString(s.length + 16) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /**
     * A value going between elements as text.
     *
     * `>` is escaped too. It is only strictly illegal in the sequence `]]>`,
     * but escaping it unconditionally costs nothing and removes the need for
     * anyone to remember that rule.
     */
    fun text(s: String): String = buildString(s.length + 16) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(ch)
        }
    }

    /**
     * A value going inside `<!-- ... -->`.
     *
     * SANITIZES rather than escapes, because XML gives no way to escape here.
     * Two rules, both from the specification: the content may not contain `--`,
     * and may not end with `-`.
     *
     * Runs of hyphens collapse to one, so `Rock -- Roll` becomes `Rock - Roll`
     * — a comment is documentation, and a faithful-enough rendering of the name
     * is worth more than refusing to build. Control characters go too: they are
     * legal in XML 1.0 comments only in a narrow set, and a name carrying a
     * bidirectional override renders the header as something other than what it
     * says.
     */
    fun comment(s: String): String {
        val collapsed = buildString(s.length) {
            var lastWasHyphen = false
            for (ch in s) {
                val safe = when {
                    ch == '\t' || ch == '\n' -> ' '
                    ch.isISOControl() -> ' '
                    // Format characters: the bidirectional overrides make a
                    // string render as something it does not say.
                    ch.category == CharCategory.FORMAT -> ' '
                    else -> ch
                }
                if (safe == '-' && lastWasHyphen) continue
                lastWasHyphen = safe == '-'
                append(safe)
            }
        }
        return collapsed.trimEnd('-', ' ')
    }
}
