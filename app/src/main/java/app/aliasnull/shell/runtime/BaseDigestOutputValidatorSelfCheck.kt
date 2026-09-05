package app.aliasnull.shell.runtime

import app.aliasnull.shell.bootstrap.BaseUserspaceArtifact

/** One validation assertion in a [BaseDigestOutputValidatorSelfCheckReport]. */
internal data class BaseDigestOutputValidatorSelfCheckCase(
    val label: String,
    val expectedMet: Boolean,
    val detail: String,
)

/** Aggregated result of one [BaseDigestOutputValidatorSelfCheck] run. */
internal data class BaseDigestOutputValidatorSelfCheckReport(
    val cases: List<BaseDigestOutputValidatorSelfCheckCase>,
) {
    val allPassed: Boolean
        get() = cases.all { it.expectedMet }

    val passedCount: Int
        get() = cases.count { it.expectedMet }
}

/**
 * Dormant, process-free and filesystem-free self-check for the strict base-digest
 * stdout validator (Part 27-T2). Every case calls the REAL
 * [BaseDigestOutputValidator.validate] on crafted strings built from the real
 * manifest ([BaseDigestOutputValidator.baseDigestExpected], which is
 * [BaseUserspaceArtifact.FILES] in insertion order) and asserts the true result:
 * the validator must accept exactly the canonical `<hex>  <name>` block - with or
 * without its single trailing newline - and reject ANY deviation (empty output, a
 * missing, extra, reordered, duplicated or unknown line, an uppercase or wrong
 * digest, a wrong separator, an interior blank line, or trailing bytes after the
 * block). No child process is spawned and no file is touched; the strings are
 * pure construction.
 *
 * The closing cases pin the cross-language manifest contract that the whole
 * controlled digest verification rests on: the digest component's fixed
 * controlled file list ([kControlledFiles] in `aliasnull_digest.cpp`, mirrored
 * here as [kControlledFilesMirror]) must name EXACTLY the same files, in the
 * same order, as [BaseUserspaceArtifact.FILES], whose count must equal
 * [BaseUserspaceArtifact.Digest.CONTROLLED_FILE_COUNT]. Because
 * [BaseDigestOutputValidator.baseDigestExpected] is that manifest order, a
 * passing device result proves a real userspace executable reproduced the
 * bootstrap's manifest digests byte-for-byte.
 *
 * Like the other self-checks in this package, this object is deliberately NOT
 * wired to any Shell command, UI or startup path; it exists so the codebase (or
 * a future test surface) can verify the validator contract on demand by calling
 * [run]. Every assertion is deterministic and depends on no device state.
 */
internal object BaseDigestOutputValidatorSelfCheck {

    /**
     * Mirror of the C++ digest component's fixed controlled file list
     * (`kControlledFiles` in `app/src/main/cpp/aliasnull_digest.cpp`). Kept here
     * so the contract the Base Digest diagnostic depends on is asserted in Kotlin
     * against the single manifest source; if one side changes without the other,
     * this check fails.
     */
    private val kControlledFilesMirror = listOf(
        "VERSION",
        "ARCH",
        "DESCRIPTION",
        "PROVENANCE.txt",
        "LICENSE.txt",
        "aliasnull_base_probe",
        "aliasnull_digest",
    )

    fun run(): BaseDigestOutputValidatorSelfCheckReport {
        val cases = mutableListOf<BaseDigestOutputValidatorSelfCheckCase>()
        val expected = BaseDigestOutputValidator.baseDigestExpected

        cases += caseOf(
            "A. the validator accepts the exact canonical digest block",
        ) {
            val validation = BaseDigestOutputValidator.validate(expected, canonicalBlockOf(expected))
            (validation.valid) to (validation.reason ?: "exact canonical block accepted")
        }

        cases += caseOf(
            "A. the validator accepts the canonical block without its single trailing newline",
        ) {
            val noFinalNewline = canonicalBlockOf(expected).removeSuffix("\n")
            val validation = BaseDigestOutputValidator.validate(expected, noFinalNewline)
            (validation.valid) to (validation.reason ?: "block without final newline accepted")
        }

        cases += caseOf(
            "B. the validator rejects empty stdout",
        ) {
            val validation = BaseDigestOutputValidator.validate(expected, "")
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects a missing digest line",
        ) {
            val lines = expectedLinesOf(expected).dropLast(1)
            val validation = BaseDigestOutputValidator.validate(expected, blockOf(lines))
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects an extra digest line",
        ) {
            val lines = expectedLinesOf(expected) + expectedLinesOf(expected).first()
            val validation = BaseDigestOutputValidator.validate(expected, blockOf(lines))
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects reordered digest lines",
        ) {
            val source = expectedLinesOf(expected)
            val lines = listOf(source[1], source[0]) + source.drop(2)
            val validation = BaseDigestOutputValidator.validate(expected, blockOf(lines))
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects an uppercase digest hex",
        ) {
            val source = expectedLinesOf(expected)
            val first = source[0]
            val uppercased = first.substring(0, 64).uppercase() + first.substring(64)
            val lines = listOf(uppercased) + source.drop(1)
            val validation = BaseDigestOutputValidator.validate(expected, blockOf(lines))
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects a wrong digest value",
        ) {
            val source = expectedLinesOf(expected)
            val first = source[0]
            val mutated = (if (first[0] == '0') "1" else "0") + first.substring(1)
            val lines = listOf(mutated) + source.drop(1)
            val validation = BaseDigestOutputValidator.validate(expected, blockOf(lines))
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects a duplicated name in place of the second line",
        ) {
            val source = expectedLinesOf(expected)
            val lines = listOf(source[0], source[0]) + source.drop(2)
            val validation = BaseDigestOutputValidator.validate(expected, blockOf(lines))
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects a line naming a file outside the manifest",
        ) {
            val source = expectedLinesOf(expected)
            val unknown = "a".repeat(64) + "  not_in_the_manifest"
            val lines = listOf(source[0], unknown) + source.drop(2)
            val validation = BaseDigestOutputValidator.validate(expected, blockOf(lines))
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects a wrong digest/name separator (single space)",
        ) {
            val source = expectedLinesOf(expected)
            val lines = listOf(source[0].replace("  ", " ")) + source.drop(1)
            val validation = BaseDigestOutputValidator.validate(expected, blockOf(lines))
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects an interior blank line",
        ) {
            val source = expectedLinesOf(expected)
            val lines = listOf(source[0], "") + source.drop(2)
            val validation = BaseDigestOutputValidator.validate(expected, blockOf(lines))
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects trailing garbage after the block",
        ) {
            val validation = BaseDigestOutputValidator.validate(
                expected,
                canonicalBlockOf(expected) + "trailing",
            )
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "B. the validator rejects a second trailing newline",
        ) {
            val validation = BaseDigestOutputValidator.validate(expected, canonicalBlockOf(expected) + "\n")
            (!validation.valid) to (validation.reason ?: "no reason given")
        }

        cases += caseOf(
            "C. the manifest names exactly the digest component's controlled files, in order",
        ) {
            val manifestNames = BaseUserspaceArtifact.FILES.keys.toList()
            (manifestNames == kControlledFilesMirror) to
                "FILES keys = $manifestNames; C++ kControlledFiles mirror = $kControlledFilesMirror"
        }

        cases += caseOf(
            "C. the controlled file count matches the frozen contract of 7",
        ) {
            val count = BaseUserspaceArtifact.FILES.size
            (count == BaseUserspaceArtifact.Digest.CONTROLLED_FILE_COUNT &&
                count == kControlledFilesMirror.size) to
                "FILES.size = $count; Digest.CONTROLLED_FILE_COUNT = " +
                BaseUserspaceArtifact.Digest.CONTROLLED_FILE_COUNT +
                "; C++ mirror count = ${kControlledFilesMirror.size}"
        }

        cases += caseOf(
            "C. the validator's expected pairs are the manifest in insertion order",
        ) {
            (expected.map { it.first } == BaseUserspaceArtifact.FILES.keys.toList()) to
                "validator expected order = ${expected.map { it.first }}"
        }

        cases += caseOf(
            "C. every manifest digest is exactly 64 lowercase hex characters",
        ) {
            val hex = Regex("^[0-9a-f]{64}$")
            val allHex = BaseUserspaceArtifact.FILES.values.all { hex.matches(it) }
            (allHex) to "all ${BaseUserspaceArtifact.FILES.size} digests are 64 lowercase hex"
        }

        return BaseDigestOutputValidatorSelfCheckReport(cases)
    }

    /** Adds one genuine assertion case; the block returns a `(expectedMet, detail)` pair. */
    private fun caseOf(
        label: String,
        assert: () -> Pair<Boolean, String>,
    ): BaseDigestOutputValidatorSelfCheckCase {
        val (expectedMet, detail) = assert()
        return BaseDigestOutputValidatorSelfCheckCase(label = label, expectedMet = expectedMet, detail = detail)
    }

    /** The `<hex>  <name>` line each expected pair produces. */
    private fun expectedLinesOf(expected: List<Pair<String, String>>): List<String> =
        expected.map { (name, hex) -> "$hex  $name" }

    /** Joins already-formatted digest lines into the validator's canonical block. */
    private fun blockOf(lines: List<String>): String =
        lines.joinToString(separator = "\n", postfix = "\n")

    /** The validator's exact canonical block for [expected]. */
    private fun canonicalBlockOf(expected: List<Pair<String, String>>): String =
        blockOf(expectedLinesOf(expected))
}
