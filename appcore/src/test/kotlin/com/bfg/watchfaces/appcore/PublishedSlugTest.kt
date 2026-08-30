package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.CatalogContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The slug is the `watchfacepush.<slug>` package suffix, so these are not
 * string-formatting tests. Two community faces sharing a package means
 * installing the second silently replaces the first on somebody's watch — no
 * error, no warning, and the kind of invisible difference that has cost this
 * project the most.
 */
class PublishedSlugTest {

    @Test
    fun `two faces with the same name get different packages`() {
        // The whole reason a published slug is not just the name slugified.
        assertTrue(PublishedSlug.matches("midnight_7f3a", "Midnight"))
        assertTrue(PublishedSlug.matches("midnight_c214", "Midnight"))
        assertEquals("midnight", PublishedSlug.stemFor("Midnight"))
    }

    @Test
    fun `the stem leaves room for the id inside the length limit`() {
        val long = "M".repeat(80)
        val slug = "${PublishedSlug.stemFor(long)}_7f3a"
        assertTrue(slug.length <= CatalogContract.MAX_SLUG_CHARS) {
            "a published slug for a long name is ${slug.length} characters, over the limit"
        }
    }

    @Test
    fun `a slug still has to be a legal package segment after the id is added`() {
        // Push wants ^[a-z][a-z0-9_]*$ and nothing else; finding out at install
        // time is far too late.
        val slug = "${PublishedSlug.stemFor("Café Crème 9")}_7f3a"
        assertTrue(Regex("^[a-z][a-z0-9_]*$").matches(slug)) { "'$slug' is not a legal package segment" }
    }

    @Test
    fun `a slug that describes a different name is not a match`() {
        assertFalse(PublishedSlug.matches("midnight_7f3a", "Daybreak"))
    }

    @Test
    fun `a plain slug with no id is not a published slug`() {
        // Locally saved faces keep their plain slug. Publishing is the only
        // moment two strangers' names can meet, so it is the only moment the id
        // is added -- and a plain one arriving at moderation means something
        // skipped that step.
        assertFalse(PublishedSlug.matches("midnight", "Midnight"))
    }

    @Test
    fun `an id of the wrong shape is not a match`() {
        assertFalse(PublishedSlug.matches("midnight_7f3", "Midnight")) { "too short" }
        assertFalse(PublishedSlug.matches("midnight_7f3ab", "Midnight")) { "too long" }
        assertFalse(PublishedSlug.matches("midnight_7F3A", "Midnight")) { "the service emits lowercase hex" }
        assertFalse(PublishedSlug.matches("midnight_zzzz", "Midnight")) { "not hex" }
    }

    @Test
    fun `a name whose slug already contains underscores still round-trips`() {
        // slugify turns spaces into underscores, so the separator is not a
        // unique character -- matching has to take the LAST one.
        val name = "Deep Sea Blue"
        assertEquals("deep_sea_blue", PublishedSlug.stemFor(name))
        assertTrue(PublishedSlug.matches("deep_sea_blue_7f3a", name))
    }
}
