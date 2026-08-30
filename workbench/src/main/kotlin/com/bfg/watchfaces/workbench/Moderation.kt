package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.appcore.Json
import java.io.File

/**
 * The verdict half of moderation: given a face from the queue, decide what it
 * deserves.
 *
 * ## Why this is separate from the thing that talks to the service
 *
 * This is the only place in the whole system where a face meets Google's XSD.
 * The catalog service cannot do it — a Worker is JavaScript, the emitter is
 * Kotlin, and Xerces is neither — so `docs/specs/catalog-service.md` moves the
 * real check here, before publication rather than at the POST.
 *
 * That makes this the load-bearing half, and the service is not deployed. If
 * the verdict logic could only be exercised through HTTP, it could not be
 * exercised at all right now. So it takes a JSON document and returns a
 * verdict, with no network anywhere in it, and [Moderate] does the talking.
 *
 * ## What a verdict is worth
 *
 * [Verdict.REFUSE] is a fact: the face does not render, or renders something
 * the schema rejects, and publishing it would put a face on the watch that
 * installs and then never appears. Nobody needs to look at those.
 *
 * [Verdict.LOOKS_FINE] is emphatically NOT "approve". It means the automated
 * half found nothing, which says nothing at all about whether the face is
 * somebody's logo, somebody's name, or a slur rendered in knotwork. Those are
 * the things a person is for, and pre-moderation is the abuse control precisely
 * because no automated check can make that call.
 */
object Moderation {

    enum class Verdict {
        /** Automated checks found nothing. A HUMAN still has to look. */
        LOOKS_FINE,

        /** Broken in a way that can be proven. Reject without a person. */
        REFUSE
    }

    data class Review(
        val id: String,
        val slug: String,
        val name: String,
        val author: String,
        val verdict: Verdict,
        val problems: List<CatalogStore.Problem>
    ) {
        /**
         * The sentence recorded as the rejection reason.
         *
         * `MODERATION.md` promises appeals are answered, and an appeal against
         * "rejected" with nothing after it cannot be. So a refusal always
         * carries what was wrong, in the words the validator used.
         */
        fun reason(): String =
            problems.take(3).joinToString("; ") { it.message }.ifBlank { "failed automated validation" }
    }

    /**
     * One queue entry, as `/admin/queue` returns it.
     *
     * The service stores `params` verbatim and hands it back the same way, so
     * what is rebuilt here is the document the submitter sent — not a
     * re-serialization that might differ.
     */
    fun review(root: File, row: Map<String, Any?>): Review {
        val id = Json.str(row, "id", "")
        val name = Json.str(row, "name", "")
        val slug = Json.str(row, "slug", "")
        val author = Json.str(row, "author", "")

        // Rebuild the catalog document the validator expects. `params` comes
        // back as the JSON TEXT the service stored, so it is re-parsed rather
        // than re-emitted -- re-emitting here would validate something the
        // submitter never sent.
        val paramsText = Json.str(row, "params", "")
        val document = """{
  "name": ${Json.quote(name)},
  "slug": ${Json.quote(slug)},
  "author": ${Json.quote(author)},
  "created": ${Json.quote(Json.str(row, "created", ""))},
  "params": $paramsText
}
"""
        val problems = CatalogStore.validateDocument(
            root = root,
            label = slug.ifBlank { id },
            text = document,
            slugRule = CatalogStore.SlugRule.PUBLISHED
        )
        return Review(
            id = id,
            slug = slug,
            name = name,
            author = author,
            verdict = if (problems.isEmpty()) Verdict.LOOKS_FINE else Verdict.REFUSE,
            problems = problems
        )
    }

    /**
     * Whether the schema is actually installed.
     *
     * Without it every validation is vacuous while still returning "no
     * problems" — which would publish schema-invalid faces and report success,
     * the exact failure this pass exists to prevent. [Moderate] refuses to run
     * rather than pass everything.
     */
    fun schemaInstalled(root: File): Boolean = WffValidator.validate(root, "<x/>") != null
}
