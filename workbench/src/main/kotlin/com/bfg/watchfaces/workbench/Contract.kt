package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.generator.CatalogContract
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import java.io.File

/**
 * Write `catalog-service/params-contract.json` from [CatalogContract].
 *
 *   ./gradlew :workbench:contract              # write it
 *   ./gradlew :workbench:contract --args="--check"   # fail if it is stale
 *
 * The file is checked in on purpose. The Worker is deployed by `wrangler` with
 * no JVM anywhere near it, so a deploy must never depend on this task having
 * been run — the same arrangement `brand` has with the launcher icons.
 * `CatalogContractTest` is what makes the committed copy trustworthy.
 */
object Contract {

    /** Where the generated contract lives, relative to the repo root. */
    const val PATH = "catalog-service/params-contract.json"

    /**
     * A real face, in the catalog's on-disk format, for the service's tests.
     *
     * Generated rather than hand-written, and that is the whole point: a
     * fixture typed out by hand proves the Worker accepts a face THE APP NEVER
     * PRODUCES. This one comes out of `FaceCodec` and `CatalogStore` — the same
     * two objects that write a real submission — so a test that accepts it is
     * evidence about the real path.
     */
    const val FIXTURE_PATH = "catalog-service/test/fixtures/face.json"

    /**
     * The fixture's parameters.
     *
     * Deliberately NOT `DialParams()`. Bare defaults exercise none of the
     * fields most likely to be validated wrongly, so this moves several
     * controls off their defaults, turns the seconds on, names a drawn date and
     * a ring, and puts a provider and a launcher into complication tokens —
     * every shape the token grammar has.
     */
    fun fixtureParams(): DialParams {
        val d = DialParams()
        return d.copy(
            engine = Engine.KNOTWORK,
            scale = 26.0,
            freq = 9,
            showSeconds = true,
            dateStyle = com.bfg.watchfaces.generator.DateStyle.WEEKDAY_MONTH_DAY,
            dateScale = com.bfg.watchfaces.generator.DateScale.LARGE,
            ring = com.bfg.watchfaces.generator.RingSource.STEPS,
            hourFormat = com.bfg.watchfaces.generator.HourFormat.TWELVE,
            complications = listOf(
                com.bfg.watchfaces.generator.ComplicationSource.WEATHER_TEMP_CONDITION,
                com.bfg.watchfaces.generator.ComplicationSource.STEP_COUNT,
                com.bfg.watchfaces.generator.ComplicationSource.HEART_RATE,
                com.bfg.watchfaces.generator.ComplicationSource.SHORTCUT_MUSIC,
                com.bfg.watchfaces.generator.ComplicationSource.WATCH_BATTERY
            ),
            providers = mapOf(
                com.bfg.watchfaces.generator.SlotPosition.LEFT to "com.example.fit/.StepsProvider"
            ),
            launchers = mapOf(
                com.bfg.watchfaces.generator.SlotPosition.RIGHT to "com.example.music/.PlayerActivity"
            )
        )
    }

    /**
     * Every key a face carries on the wire, read off the codec rather than
     * listed here. `toQuery` is the canonical serialization — if a parameter is
     * added and this list did not change, the parameter is not being written.
     */
    fun fields(): List<String> =
        FaceCodec.toQuery(DialParams()).split("&").map { it.substringBefore("=") }

    @JvmStatic
    fun main(args: Array<String>) {
        val checkOnly = args.contains("--check")
        val root = RepoRoot.find()
        val file = File(root, PATH)
        val wanted = CatalogContract.json(fields())

        val fixtureFile = File(root, FIXTURE_PATH)
        val wantedFixture = CatalogStore.toJson(
            CatalogStore.Entry(
                slug = "fixture_face",
                name = "Fixture Face",
                author = "The Test Suite",
                // Fixed, not Instant.now(): a generated file that changes on
                // every run is a diff that never means anything.
                created = "2026-08-30T00:00:00Z",
                params = fixtureParams()
            )
        )

        if (checkOnly) {
            val actual = if (file.isFile) file.readText() else ""
            val actualFixture = if (fixtureFile.isFile) fixtureFile.readText() else ""
            if (actual == wanted && actualFixture == wantedFixture) {
                println("$PATH and $FIXTURE_PATH are current")
                return
            }
            System.err.println("$PATH is STALE.")
            System.err.println("  The Worker validates submissions against this file, so a stale copy")
            System.err.println("  accepts values the generator would refuse. Run:")
            System.err.println("      ./gradlew :workbench:contract")
            kotlin.system.exitProcess(1)
        }

        file.parentFile.mkdirs()
        val changed = !file.isFile || file.readText() != wanted
        file.writeText(wanted)
        println(if (changed) "wrote ${file.path}" else "${file.path} was already current")

        fixtureFile.parentFile.mkdirs()
        val fixtureChanged = !fixtureFile.isFile || fixtureFile.readText() != wantedFixture
        fixtureFile.writeText(wantedFixture)
        println(if (fixtureChanged) "wrote ${fixtureFile.path}" else "${fixtureFile.path} was already current")
    }
}
