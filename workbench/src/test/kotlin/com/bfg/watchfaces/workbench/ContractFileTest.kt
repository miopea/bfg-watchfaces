package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.generator.CatalogContract
import com.bfg.watchfaces.generator.DialParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `catalog-service/params-contract.json` is committed, and the Worker that
 * reads it is deployed by `wrangler` with no JVM in sight. So nothing at deploy
 * time can notice that the file has gone stale — this is the only thing that
 * can.
 *
 * A stale contract does not fail loudly. It accepts a value the generator would
 * refuse, or refuses one it would accept, on a public endpoint, silently. That
 * is why this is a test and not a build step somebody remembers to run.
 */
class ContractFileTest {

    private val root = RepoRoot.find()

    @Test
    fun `the committed contract is what the generator would write today`() {
        val file = File(root, Contract.PATH)
        assertTrue(file.isFile) { "${Contract.PATH} is missing. Run ./gradlew :workbench:contract" }
        assertEquals(CatalogContract.json(Contract.fields()), file.readText()) {
            "${Contract.PATH} is stale -- the Worker is validating against an old file format. " +
                "Run ./gradlew :workbench:contract and commit the result."
        }
    }

    /**
     * The field list is read off `FaceCodec.toQuery`, so a new parameter
     * appears in the contract automatically. This checks the reading is
     * actually happening rather than a list having been pasted in.
     */
    @Test
    fun `the field list is the codec's own keys`() {
        val fromCodec = FaceCodec.toQuery(DialParams()).split("&").map { it.substringBefore("=") }
        assertEquals(fromCodec.sorted(), Contract.fields().sorted())
        assertTrue(fromCodec.contains("generatorVersion")) {
            "toQuery no longer writes generatorVersion, which silently upgrades every stored face"
        }
    }

    /**
     * Every control the inventory offers must be a field the codec writes.
     *
     * A slider whose value is never serialized is a control that appears to
     * work and is lost on save. Checked here rather than in `:generator`
     * because only this module can see both halves.
     */
    @Test
    fun `every control's id is a field the codec writes`() {
        val fields = Contract.fields().toSet()
        for (c in com.bfg.watchfaces.generator.ControlInventory.CONTROLS) {
            assertTrue(fields.contains(c.id)) {
                "control '${c.id}' is not written by FaceCodec.toQuery, so moving it is not saved"
            }
        }
    }
}
