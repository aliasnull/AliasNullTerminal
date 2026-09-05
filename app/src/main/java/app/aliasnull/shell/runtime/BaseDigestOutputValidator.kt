package app.aliasnull.shell.runtime

import app.aliasnull.shell.bootstrap.BaseUserspaceArtifact

/**
 * Strict validator for the controlled base-digest diagnostic's stdout (Part
 * 27-T2).
 *
 * The bundled digest component (`aliasnull_digest.cpp`) prints exactly one line
 * per installed base-userspace file in its controlled mode:
 *
 * ```
 * <64 lowercase-hex sha256>  <manifest-relative file name>
 * ```
 *
 * in EXACTLY the order of the [expected] manifest (itself the insertion order of
 * [BaseUserspaceArtifact.FILES], which the source's fixed controlled file list
 * mirrors). The validator therefore requires [stdout] to equal the exact,
 * deterministic block derived from [expected] - one `<hex>  <name>` line per
 * entry, in order, ending in a single newline. This is deliberately an exact
 * match, not a parser: any deviation at all - a missing, extra or duplicate
 * line, a reordered line, an unknown name, an uppercase or wrong digest, a
 * wrong separator, trailing or interior whitespace, or any other byte difference
 * - fails the check. Because every hex must equal the [expected] manifest value,
 * a passing result means a real userspace executable independently re-hashed the
 * installed base files and reproduced the bootstrap's manifest digests exactly.
 *
 * The validator is pure and filesystem-free, so the dormant self-check can
 * exercise its accept and reject cases against crafted strings. The expected
 * block may carry no trailing newline and still match (a captured child's last
 * line is always newline-terminated, but the two acceptable forms keep the
 * validator robust); any other trailing bytes fail.
 */
internal object BaseDigestOutputValidator {

    /** Outcome of validating one digest stdout: [valid] true only on an exact match. */
    data class Validation(val valid: Boolean, val reason: String? = null)

    /**
     * The authoritative expected file/digest pairs for the controlled base-digest
     * diagnostic: every [BaseUserspaceArtifact.FILES] entry in manifest insertion
     * order (the order the source's fixed controlled file list mirrors). The
     * digest component hashes exactly these installed files, so comparing its
     * output against this list is the whole of the controlled verification.
     */
    val baseDigestExpected: List<Pair<String, String>>
        get() = BaseUserspaceArtifact.FILES.toList()

    /**
     * Validates [stdout] against [expected] (an ordered file-name -> expected
     * lowercase-hex list). True only when [stdout] is exactly the expected block
     * (see the object doc), optionally with or without its single trailing
     * newline. Pure and total: never throws.
     */
    fun validate(expected: List<Pair<String, String>>, stdout: String): Validation {
        val canonical = buildString {
            for ((name, hex) in expected) {
                append(hex).append("  ").append(name).append('\n')
            }
        }
        val accepted = stdout == canonical || stdout == canonical.removeSuffix("\n")
        return if (accepted) {
            Validation(valid = true)
        } else {
            Validation(valid = false, reason = mismatchReason(expected, stdout))
        }
    }

    /**
     * A short, concrete reason [stdout] did not match [expected]: a line-count
     * difference, the first non-matching line (by position), or a formatting
     * difference when the line content itself matches. Best-effort detail for the
     * strictness verdict, never used to decide it.
     */
    private fun mismatchReason(expected: List<Pair<String, String>>, stdout: String): String {
        if (expected.isEmpty()) {
            return "expected no digest output, but got '${display(stdout)}'"
        }
        val expectedLines = expected.map { (name, hex) -> "$hex  $name" }
        val body = if (stdout.endsWith('\n')) stdout.dropLast(1) else stdout
        val actualLines = body.split('\n')
        if (actualLines.size != expectedLines.size) {
            return "expected exactly ${expectedLines.size} digest lines, one per installed base " +
                "file, in manifest order; got ${actualLines.size}"
        }
        for (index in expectedLines.indices) {
            if (actualLines[index] != expectedLines[index]) {
                val (name, _) = expected[index]
                return "digest line ${index + 1} for '$name' does not equal the expected " +
                    "'${expectedLines[index]}'; got '${display(actualLines[index])}'"
            }
        }
        return "digest output does not match exactly one '<hex>  <name>' line per installed " +
            "base file (a formatting or termination difference)"
    }

    private fun display(line: String): String = if (line.isEmpty()) "(empty)" else line
}
