package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CycleSyncPlanTest {

    /**
     * The reinstall, which is the whole reason this object exists.
     *
     * Uninstalling the phone app revokes the Health Connect grant and wipes
     * `filesDir`. The watch is party to neither and keeps the start date it was
     * last sent. The old rule read that state as "she never turned this on" and
     * said nothing, leaving a wrist counting from a date the phone had
     * forgotten.
     *
     * Every term here is false and the answer must still be yes.
     */
    @Test
    fun `a fresh install that has never spoken to a watch speaks once`() {
        assertTrue(
            CycleSyncPlan.shouldSync(
                granted = false,
                phoneRemembersDate = false,
                watchToldThisInstall = false
            )
        )
    }

    /**
     * And then stops, which is what buys the correctness above.
     *
     * Someone who has never opened the cycle feature gets one push on the first
     * launch of an install and silence after it. `onResume` is often, and a
     * node query plus two Data Layer messages on every return to the app is not
     * free.
     */
    @Test
    fun `an install that has already told a watch and has nothing stays quiet`() {
        assertFalse(
            CycleSyncPlan.shouldSync(
                granted = false,
                phoneRemembersDate = false,
                watchToldThisInstall = true
            )
        )
    }

    /** She is using the feature. Always sync, however often that is. */
    @Test
    fun `a granted read always syncs`() {
        assertTrue(
            CycleSyncPlan.shouldSync(
                granted = true,
                phoneRemembersDate = false,
                watchToldThisInstall = true
            )
        )
    }

    /**
     * The REVOKE, which the old rule did get right and must keep getting right.
     *
     * The permission is gone but the cached date proves this install once had
     * it, so the sync runs and pushes a clear. A watch must not keep counting
     * from something she has withdrawn.
     */
    @Test
    fun `a revoked permission with a remembered date still syncs, to clear it`() {
        assertTrue(
            CycleSyncPlan.shouldSync(
                granted = false,
                phoneRemembersDate = true,
                watchToldThisInstall = true
            )
        )
    }

    /** Absent reads as "not told", so the doubt costs a sync rather than a wrong number. */
    @Test
    fun `no flag on disk reads as never told`(@TempDir dir: File) {
        assertFalse(CycleSyncPlan.wasTold(dir))
    }

    @Test
    fun `the flag survives being written and read back`(@TempDir dir: File) {
        CycleSyncPlan.markTold(dir)
        assertTrue(CycleSyncPlan.wasTold(dir))
    }

    /**
     * The flag lives where an uninstall takes it.
     *
     * That is the entire mechanism: the act that strands the watch is the same
     * act that re-arms the push which rescues it. A flag kept anywhere that
     * survived an uninstall would defeat the fix silently.
     */
    @Test
    fun `the flag is stored under the given root, so an uninstall clears it`(@TempDir dir: File) {
        CycleSyncPlan.markTold(dir)
        assertTrue(dir.listFiles().orEmpty().any { it.isFile })
        dir.listFiles().orEmpty().forEach { it.delete() }
        assertFalse(CycleSyncPlan.wasTold(dir))
    }
}
