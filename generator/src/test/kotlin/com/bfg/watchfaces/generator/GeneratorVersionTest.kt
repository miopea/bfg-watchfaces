package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Community faces are distributed as parameters, so the generator IS the file
 * format. These tests exist to make breaking that format loud.
 */
class GeneratorVersionTest {

    @Test
    fun `bumping CURRENT_GENERATOR_VERSION is deliberate`() {
        assertEquals(1, CURRENT_GENERATOR_VERSION,
            "You changed CURRENT_GENERATOR_VERSION. That is fine ONLY if you added a new " +
            "branch in PatternEngines.paths() and left every older branch untouched. " +
            "Existing community faces must keep rendering exactly as their authors saw them. " +
            "Update this assertion and add golden coverage for the new version.")
    }

    @Test
    fun `params reject an unknown generator version`() {
        assertThrows(IllegalArgumentException::class.java) {
            DialParams(generatorVersion = CURRENT_GENERATOR_VERSION + 1)
        }
    }

    @Test
    fun `params reject malformed colours`() {
        assertThrows(IllegalArgumentException::class.java) { DialParams(dialColor = "7D7369") }
        assertThrows(IllegalArgumentException::class.java) { DialParams(inkColor = "#FFF") }
    }

    @Test
    fun `push package names follow the required convention`() {
        assertEquals(
            "com.bfg.watchfaces.watchfacepush.silver_sand",
            WffEmitter.pushPackageName("com.bfg.watchfaces", "silver_sand")
        )
        assertThrows(IllegalArgumentException::class.java) {
            WffEmitter.pushPackageName("com.bfg.watchfaces", "Silver-Sand")
        }
    }
}
