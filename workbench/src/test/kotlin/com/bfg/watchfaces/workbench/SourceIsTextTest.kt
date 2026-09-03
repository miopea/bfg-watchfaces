package com.bfg.watchfaces.workbench

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No source file in this repo may contain a NUL or a stray control byte.
 *
 * ## The incident
 *
 * `PhoneNoteTest.kt` carried two literal NUL bytes inside a string literal —
 * `writeText` of a deliberately corrupt file, spelled with the raw bytes rather
 * than escapes. The file was valid UTF-8 and matched HEAD, and nothing was
 * corrupted. But `file(1)` reported it as `data`, and every text tool in the
 * chain then skipped it WITHOUT SAYING SO:
 *
 * ```text
 * ugrep -c "@Test" PhoneNoteTest.kt   ->  no output, exit 1   (reads as ABSENT)
 * GNU grep -o "@Test" PhoneNoteTest.kt -> "binary file matches", no matches
 * actual count, read as bytes          ->  8
 * ```
 *
 * That produced a real wrong answer: counting tests across `:appcore` by grep
 * gave 175 instead of 183, and the missing 8 were this one file. An empty
 * result from a tool that never opened the file is indistinguishable from a
 * genuine absence, which is what makes this worth a standing guard rather than
 * a one-time fix.
 *
 * It is not hypothetical and not rare: while fixing it, the same accident
 * happened twice more — once in the ticket describing it, and once in a probe
 * written to verify the fix. Raw NULs travel through shells and editors
 * invisibly.
 *
 * ## Why this test reads bytes
 *
 * Deliberately NOT grep. Grep is precisely the tool that cannot see the
 * problem — a search for the offending files would skip exactly the files that
 * have it. This opens each file and counts bytes.
 *
 * The fix is always to spell the byte as an escape. `"\u0000"` compiles to the
 * same U+0000 and `writeText` emits the same `0x00`; measured, not assumed.
 */
class SourceIsTextTest {

    private val root = RepoRoot.find()

    /** Binary by nature. Anything here is expected to contain arbitrary bytes. */
    private val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "avif", "bmp", "ico",
        "jar", "aab", "apk", "class", "so", "bin", "zip",
        "keystore", "jks", "ttf", "otf", "woff", "woff2", "pdf"
    )

    /** Tab, newline and carriage return are ordinary; every other C0 is not. */
    private fun straysIn(bytes: ByteArray): Int =
        bytes.count { val b = it.toInt() and 0xFF; b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D }

    /**
     * The files IN this repo, asked of git rather than of the filesystem.
     *
     * A directory walk with a blocklist was the first attempt and it was wrong:
     * it found 200-odd Rust `.rlib` and `.rmeta` files under
     * `scripts/pack-java/target/`, which are gitignored build output and
     * legitimately binary. Blocklisting "target" would have worked until the
     * next tool invented a build directory. "Tracked by git" is the boundary
     * this test actually means.
     */
    private fun trackedFiles(): List<File> {
        val out = ProcessBuilder("git", "ls-files", "-z")
            .directory(root).redirectErrorStream(false).start()
        val paths = out.inputStream.bufferedReader().readText().split('\u0000')
        check(out.waitFor() == 0) { "git ls-files failed; this test needs a git checkout" }
        return paths.filter { it.isNotEmpty() }.map { File(root, it) }.filter { it.isFile }
    }

    @Test
    fun `no source file carries a NUL or a stray control byte`() {
        val offenders = mutableListOf<String>()
        var scanned = 0

        for (f in trackedFiles()) {
            if (f.extension.lowercase() in binaryExtensions) continue
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            scanned++
            val nul = bytes.count { it.toInt() == 0 }
            val stray = straysIn(bytes)
            if (nul > 0 || stray > 0) offenders += "${f.relativeTo(root)}: NUL=$nul stray=$stray"
        }

        // A pass that scanned nothing would be a pass that proves nothing.
        assertTrue(scanned > 100) { "only scanned $scanned files; this test is not reaching the repo" }
        assertTrue(offenders.isEmpty()) {
            "these read as BINARY to grep and file(1), so text tooling skips them silently:\n" +
                offenders.joinToString("\n") { "  $it" } +
                "\nSpell the byte as an escape instead -- a unicode escape emits the same 0x00."
        }
    }
}
