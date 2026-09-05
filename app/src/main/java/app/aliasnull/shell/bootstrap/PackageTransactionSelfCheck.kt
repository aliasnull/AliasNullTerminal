package app.aliasnull.shell.bootstrap

import java.io.File

/** One validation assertion in a [PackageTransactionSelfCheckReport]. */
internal data class PackageTransactionSelfCheckCase(
    val label: String,
    val expectedMet: Boolean,
    val detail: String,
)

/** Aggregated result of one [PackageTransactionSelfCheck] run. */
internal data class PackageTransactionSelfCheckReport(
    val cases: List<PackageTransactionSelfCheckCase>,
) {
    val allPassed: Boolean
        get() = cases.all { it.expectedMet }

    val passedCount: Int
        get() = cases.count { it.expectedMet }
}

/**
 * Dormant, process-free self-check for the Part 27-V data-only package install
 * transaction. Every case calls the REAL [PackageTransaction.install] and
 * [PackageTransaction.reconcile] against small, isolated, freshly wiped scratch
 * filesystem roots under the caller-provided [scratchRoot], so no check touches
 * the real runtime base userspace, no package content is ever executed, and
 * every filesystem operation is real. Symlink scenarios are built with the real
 * `lstat`-visible [android.system.Os.symlink]; nothing is mocked and no
 * directory is ever reported installed by existence alone.
 *
 * The coverage mirrors the milestone's deterministic transaction logic: a valid
 * end-to-end install and its INSTALLED state record / live-tree exactness; base
 * userspace immutability; the executable flag staying inert (data-only storage);
 * the source-validation rejection classes (malformed manifest, unsupported
 * architecture/format, digest mismatch, missing/extra payload, symlink and
 * directory-type payloads, reserved-name collisions, a payload path that demands
 * a payload file as a parent, path traversal); dependency and conflict outcomes
 * (absent, exact-version mismatch and exact-version satisfaction, conflict
 * hit/miss, replaces remaining informational); target/state conflicts
 * (reinstall, INSTALLED-with-missing-live, orphan live directory); and every
 * reconciliation case A-E (stale staging removal, verified-but-never-promoted
 * staging removal with no auto-install, interrupted-promotion completion via the
 * live verified marker, staged/live identity-mismatch refusal, INSTALLED-with-
 * missing-live inconsistency, plain-orphan reporting, and leftover-staging tidy).
 *
 * Like the other self-checks in this codebase, this object is deliberately NOT
 * wired to any Shell command, UI, startup path or package runtime; it exists so
 * the codebase (or a future test surface) can verify the transaction contract on
 * demand by calling [run]. It performs blocking filesystem work and MUST be
 * called from a background thread, never the Android main thread.
 */
internal object PackageTransactionSelfCheck {

    fun run(scratchRoot: File): PackageTransactionSelfCheckReport {
        wipe(scratchRoot)
        if (!scratchRoot.mkdirs()) {
            return report(
                case("setup: the scratch root is usable",
                    false to "could not create scratch root ${scratchRoot.path}"),
            )
        }
        return report(
            // ---- Setup ----
            case("1. setup: the scratch root is usable", setup(scratchRoot)),

            // ---- A. Valid install and its durable results ----
            case("A. a valid package install succeeds and lands the expected tree",
                validInstallSucceeds(scratchRoot)),
            case("A. the INSTALLED state record carries version, arch and the manifest SHA-256",
                stateRecorded(scratchRoot)),
            case("A. the promoted live tree exact-validates and the marker and staging are cleaned",
                liveTreeClean(scratchRoot)),
            case("A. the base userspace subtree is never touched by an install",
                baseUntouched(scratchRoot)),
            case("A. an exec-flagged payload is stored as data with no owner-execute bit",
                execFlagInert(scratchRoot)),

            // ---- B. Source-validation rejection classes (PHASE A) ----
            case("B. a malformed reserved manifest is refused as Malformed",
                malformedManifestRejected(scratchRoot)),
            case("B. a source with no reserved manifest is refused as Malformed",
                missingManifestRejected(scratchRoot)),
            case("B. an unsupported package architecture is refused as Unsupported",
                unsupportedArchRejected(scratchRoot)),
            case("B. an unsupported manifest format version is refused as Unsupported",
                unsupportedFormatRejected(scratchRoot)),
            case("B. a payload digest mismatch is refused as IntegrityFailed",
                digestMismatchRejected(scratchRoot)),
            case("B. a missing payload file is refused as IntegrityFailed",
                missingPayloadRejected(scratchRoot)),
            case("B. an extra undeclared file is refused as IntegrityFailed",
                extraPayloadRejected(scratchRoot)),
            case("B. a symlinked payload is refused as PolicyRejected",
                symlinkRejected(scratchRoot)),
            case("B. a directory where a payload file is declared is refused as PolicyRejected",
                directoryAsPayloadRejected(scratchRoot)),
            case("B. a payload path equal to the reserved manifest name is refused as PolicyRejected",
                reservedManifestCollisionRejected(scratchRoot)),
            case("B. a payload path equal to the reserved marker name is refused as PolicyRejected",
                reservedMarkerCollisionRejected(scratchRoot)),
            case("B. a payload path that needs a payload file as its parent is refused as PolicyRejected",
                nestedCollisionRejected(scratchRoot)),
            case("B. a path-traversal payload is refused at parse time (never copied)",
                traversalRejected(scratchRoot)),

            // ---- C. Dependency and conflict outcomes ----
            case("C. a missing dependency is refused as DependencyFailed",
                missingDependencyRejected(scratchRoot)),
            case("C. a dependency on an exact version that is not installed is refused as DependencyFailed",
                versionMismatchDependencyRejected(scratchRoot)),
            case("C. a dependency on an exact installed version succeeds",
                satisfiedDependencySucceeds(scratchRoot)),
            case("C. a conflict with an installed package is refused as ConflictDetected",
                conflictRejected(scratchRoot)),
            case("C. a versioned conflict that does not match the installed version is allowed",
                versionedConflictAllowed(scratchRoot)),
            case("C. replaces is informational and never gates an install",
                replacesInformational(scratchRoot)),

            // ---- D. Target / state conflicts (PHASE B) ----
            case("D. re-installing an already-installed package is refused as TransactionConflict",
                reinstallRejected(scratchRoot)),
            case("D. installing over an INSTALLED record with no live tree is refused as InconsistentState",
                installedWithoutLiveRejected(scratchRoot)),
            case("D. installing over an orphan live directory is refused as InconsistentState",
                orphanLiveRejected(scratchRoot)),

            // ---- E. Reconciliation cases (A-E) ----
            case("E. reconcile removes incomplete stale staging (CASE A)",
                staleStagingRemoved(scratchRoot)),
            case("E. reconcile removes verified-but-never-promoted staging without auto-installing (CASE B)",
                verifiedStagingRemoved(scratchRoot)),
            case("E. reconcile completes an interrupted post-promotion via the live marker",
                interruptedPromotionCompleted(scratchRoot)),
            case("E. reconcile refuses a staged/live identity mismatch and deletes nothing",
                identityMismatchRefused(scratchRoot)),
            case("E. reconcile reports INSTALLED-with-missing-live as inconsistent (CASE D)",
                installedMissingLiveReported(scratchRoot)),
            case("E. reconcile reports a plain orphan live tree, never auto-marking or deleting it (CASE E)",
                plainOrphanReported(scratchRoot)),
            case("E. reconcile tidies leftover verified staging and a live marker over an installed package",
                leftoverTidied(scratchRoot)),
        )
    }

    // ---- Setup ----

    private fun setup(scratchRoot: File): Pair<Boolean, String> =
        true to "scratch root ${scratchRoot.path} is writable"

    // ---- A. Valid install and its durable results ----

    private fun validInstallSucceeds(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "a_valid")
        val fixture = alphaFixture()
        val result = installIn(root, "src", fixture)
        val met = result is PackageInstallResult.Success
        val detail = when (result) {
            is PackageInstallResult.Success -> {
                val live = File(root, "userspace/packages/alpha")
                val shapeOk = live.isDirectory &&
                    File(live, PackageLayout.RESERVED_MANIFEST_FILE).isFile &&
                    File(live, "payload.txt").isFile &&
                    File(root, "tmp/staging-packages/alpha").exists().not()
                "Success(${result.name}@${result.version}, ${result.arch}); live dir present=$shapeOk"
            }
            else -> "expected Success but got ${describe(result)}"
        }
        return (met && result is PackageInstallResult.Success &&
            File(root, "userspace/packages/alpha").isDirectory) to detail
    }

    private fun stateRecorded(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "a_state")
        val fixture = alphaFixture()
        val result = installIn(root, "src", fixture)
        if (result !is PackageInstallResult.Success) {
            return false to "expected Success but got ${describe(result)}"
        }
        val record = PackageStateFile.read(PackageStateFile.stateFileFor(root, "alpha"))
        val expectedSha = BaseUserspaceFiles.digestHex(fixture.manifest.canonicalBytes)
        val met = record != null && record.installed &&
            record.version == "1.0" && record.arch == "arm64-v8a" &&
            record.manifestSha256 == expectedSha && result.manifestSha256 == expectedSha
        val detail = "record=${record}; success manifestSha256=${result.manifestSha256}; expected $expectedSha"
        return met to detail
    }

    private fun liveTreeClean(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "a_live")
        val fixture = alphaFixture()
        val result = installIn(root, "src", fixture)
        if (result !is PackageInstallResult.Success) {
            return false to "expected Success but got ${describe(result)}"
        }
        val live = File(root, "userspace/packages/alpha")
        val report = PackageTreeValidator.validate(live, fixture.manifest, allowVerifiedMarker = false)
        val markerGone = File(live, PackageLayout.VERIFIED_MARKER_FILE).exists().not()
        val stagingGone = File(root, "tmp/staging-packages/alpha").exists().not()
        val met = report.valid && markerGone && stagingGone
        val detail = "liveValid=${report.valid} markerGone=$markerGone stagingGone=$stagingGone " +
            if (report.valid) "" else "unexpected=${report.unexpectedObjects} missing=${report.missingFiles}"
        return met to detail
    }

    private fun baseUntouched(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "a_base") { r ->
            val base = File(r, "userspace/base")
            base.mkdirs()
            writeText(File(base, "sentinel.txt"), "immutable")
        }
        val before = File(root, "userspace/base/sentinel.txt").readText()
        val result = installIn(root, "src", alphaFixture())
        if (result !is PackageInstallResult.Success) {
            return false to "expected Success but got ${describe(result)}"
        }
        val after = File(root, "userspace/base/sentinel.txt").readText()
        val baseFiles = File(root, "userspace/base").listFiles()?.map { it.name }.orEmpty().sorted()
        val met = before == after && after == "immutable" && baseFiles == listOf("sentinel.txt")
        val detail = "sentinel preserved=$before; base files now $baseFiles"
        return met to detail
    }

    private fun execFlagInert(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "a_exec")
        val fixture = fixtureOf(
            name = "exectool",
            version = "1.0",
            payloads = mapOf("bin/tool" to "#!/data/not-really\n", "data/notes.txt" to "plain data"),
            exec = setOf("bin/tool"),
        )
        val result = installIn(root, "src", fixture)
        if (result !is PackageInstallResult.Success) {
            return false to "expected Success but got ${describe(result)}"
        }
        val liveFile = File(root, "userspace/packages/exectool/bin/tool")
        val mode = BaseUserspaceFiles.modeBits(liveFile)
        val execBitSet = mode != null && BaseUserspaceFiles.isOwnerExecutableMode(mode)
        val contentKept = liveFile.readBytes().contentEquals(
            fixture.payloads.getValue("bin/tool"),
        )
        val met = mode != null && !execBitSet && contentKept
        val detail = "mode=${mode?.let { Integer.toOctalString(it and 0xfff) }} execBit=$execBitSet contentKept=$contentKept"
        return met to detail
    }

    // ---- B. Source-validation rejection classes (PHASE A) ----

    private fun malformedManifestRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_malformed") { src ->
            writeText(File(src, PackageLayout.RESERVED_MANIFEST_FILE), "this is not a manifest")
        }
        val met = result is PackageInstallResult.Malformed
        return met to "expected Malformed; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun missingManifestRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_nomanifest") { src ->
            writeText(File(src, "README"), "a source without the reserved manifest")
        }
        val met = result is PackageInstallResult.Malformed
        return met to "expected Malformed; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun unsupportedArchRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_arch") { src ->
            writeText(File(src, PackageLayout.RESERVED_MANIFEST_FILE), manifestText(
                arch = "x86_64",
            ))
        }
        val met = result is PackageInstallResult.Unsupported
        return met to "expected Unsupported; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun unsupportedFormatRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_format") { src ->
            writeText(File(src, PackageLayout.RESERVED_MANIFEST_FILE), manifestText(
                formatVersion = 2,
            ))
        }
        val met = result is PackageInstallResult.Unsupported
        return met to "expected Unsupported; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun digestMismatchRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_digest") { src ->
            writeFixtureInto(src, digestFixture())
            writeText(File(src, "payload.txt"), "tampered bytes do not match the declared digest")
        }
        val met = result is PackageInstallResult.IntegrityFailed
        return met to "expected IntegrityFailed; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun missingPayloadRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_missing") { src ->
            writeFixtureInto(src, alphaFixture())
            File(src, "payload.txt").delete()
        }
        val met = result is PackageInstallResult.IntegrityFailed
        return met to "expected IntegrityFailed; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun extraPayloadRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_extra") { src ->
            writeFixtureInto(src, alphaFixture())
            writeText(File(src, "stray.txt"), "this file is not declared by the manifest")
        }
        val met = result is PackageInstallResult.IntegrityFailed
        return met to "expected IntegrityFailed; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun symlinkRejected(scratchRoot: File): Pair<Boolean, String> {
        var linkCreated = false
        val (result) = installSource(scratchRoot, "b_symlink") { src ->
            writeFixtureInto(src, alphaFixture(), skip = setOf("payload.txt"))
            linkCreated = runCatching {
                android.system.Os.symlink("somewhere-else", File(src, "payload.txt").path)
                true
            }.getOrDefault(false)
        }
        if (!linkCreated) {
            return false to "environment refused Os.symlink; the rejection could not be exercised"
        }
        val met = result is PackageInstallResult.PolicyRejected
        return met to "expected PolicyRejected; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun directoryAsPayloadRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_dirfile") { src ->
            writeFixtureInto(src, fixtureOf(
                name = "dirpkg", version = "1.0", payloads = mapOf("dirfile" to "declared as a file"),
            ))
            val target = File(src, "dirfile")
            target.delete()
            target.mkdirs()
        }
        val met = result is PackageInstallResult.PolicyRejected
        return met to "expected PolicyRejected; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun reservedManifestCollisionRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_reserved") { src ->
            // The payload list claims a file named exactly "manifest"; the reserved
            // canonical manifest must still be what sits at that path, so the
            // payload copy is skipped and the structural collision is what rejects.
            writeFixtureInto(src, fixtureOf(
                name = "collide", version = "1.0",
                payloads = mapOf(PackageLayout.RESERVED_MANIFEST_FILE to "x", "readme.txt" to "hello"),
            ), skip = setOf(PackageLayout.RESERVED_MANIFEST_FILE))
        }
        val met = result is PackageInstallResult.PolicyRejected
        return met to "expected PolicyRejected; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun reservedMarkerCollisionRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_marker") { src ->
            writeFixtureInto(src, fixtureOf(
                name = "markpkg", version = "1.0",
                payloads = mapOf(PackageLayout.VERIFIED_MARKER_FILE to "payload data"),
            ))
        }
        val met = result is PackageInstallResult.PolicyRejected
        return met to "expected PolicyRejected; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun nestedCollisionRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_nested") { src ->
            // "data" is declared as a payload file and also as the parent directory
            // of "data/inner.txt"; the structural collision rejects before disk use.
            writeFixtureInto(src, fixtureOf(
                name = "nestpkg", version = "1.0",
                payloads = mapOf("data" to "A", "data/inner.txt" to "B"),
            ), skip = setOf("data/inner.txt"))
        }
        val met = result is PackageInstallResult.PolicyRejected
        return met to "expected PolicyRejected; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun traversalRejected(scratchRoot: File): Pair<Boolean, String> {
        val (result) = installSource(scratchRoot, "b_traversal") { src ->
            writeText(File(src, PackageLayout.RESERVED_MANIFEST_FILE), manifestText(
                fileLine = "file ../escape.txt ${"a".repeat(64)}",
            ))
        }
        val met = result is PackageInstallResult.Malformed
        return met to "expected Malformed (parse gate); ${if (met) "rejected" else "got " + describe(result)}"
    }

    // ---- C. Dependency and conflict outcomes ----

    private fun missingDependencyRejected(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "c_missingdep")
        val main = fixtureOf(
            name = "needy", version = "1.0",
            payloads = mapOf("payload.txt" to "needy"),
            depends = listOf(PackageReference("alpha")),
        )
        val result = installIn(root, "src", main)
        val met = result is PackageInstallResult.DependencyFailed
        return met to "expected DependencyFailed; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun versionMismatchDependencyRejected(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "c_verdep")
        val first = installIn(root, "srcDep", alphaFixture())
        if (first !is PackageInstallResult.Success) {
            return false to "setup dependency install failed: ${describe(first)}"
        }
        val main = fixtureOf(
            name = "needy", version = "1.0",
            payloads = mapOf("payload.txt" to "needy"),
            depends = listOf(PackageReference("alpha", "9.9")),
        )
        val result = installIn(root, "srcMain", main)
        val met = result is PackageInstallResult.DependencyFailed
        return met to "expected DependencyFailed; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun satisfiedDependencySucceeds(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "c_satdep")
        val first = installIn(root, "srcDep", alphaFixture())
        if (first !is PackageInstallResult.Success) {
            return false to "setup dependency install failed: ${describe(first)}"
        }
        val main = fixtureOf(
            name = "needy", version = "1.0",
            payloads = mapOf("payload.txt" to "needy"),
            depends = listOf(PackageReference("alpha", "1.0")),
        )
        val result = installIn(root, "srcMain", main)
        val met = result is PackageInstallResult.Success
        return met to "expected Success; ${if (met) "installed" else "got " + describe(result)}"
    }

    private fun conflictRejected(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "c_conflict")
        val first = installIn(root, "srcDep", alphaFixture())
        if (first !is PackageInstallResult.Success) {
            return false to "setup conflictor install failed: ${describe(first)}"
        }
        val main = fixtureOf(
            name = "conflictor", version = "1.0",
            payloads = mapOf("payload.txt" to "conflictor"),
            conflicts = listOf(PackageReference("alpha")),
        )
        val result = installIn(root, "srcMain", main)
        val met = result is PackageInstallResult.ConflictDetected
        return met to "expected ConflictDetected; ${if (met) "rejected" else "got " + describe(result)}"
    }

    private fun versionedConflictAllowed(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "c_verconflict")
        val first = installIn(root, "srcDep", alphaFixture())
        if (first !is PackageInstallResult.Success) {
            return false to "setup conflicting install failed: ${describe(first)}"
        }
        val main = fixtureOf(
            name = "conflictor", version = "1.0",
            payloads = mapOf("payload.txt" to "conflictor"),
            conflicts = listOf(PackageReference("alpha", "9.9")),
        )
        val result = installIn(root, "srcMain", main)
        val met = result is PackageInstallResult.Success
        return met to "expected Success (conflict version 9.9 != installed 1.0); " +
            "${if (met) "installed" else "got " + describe(result)}"
    }

    private fun replacesInformational(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "c_replaces")
        val fixture = fixtureOf(
            name = "replacer", version = "2.0",
            payloads = mapOf("payload.txt" to "replacer"),
            replaces = listOf(PackageReference("replaced-old"), PackageReference("replaced", "1.0")),
        )
        val result = installIn(root, "src", fixture)
        val met = result is PackageInstallResult.Success
        return met to "expected Success; ${if (met) "installed" else "got " + describe(result)}"
    }

    // ---- D. Target / state conflicts (PHASE B) ----

    private fun reinstallRejected(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "d_reinstall")
        val first = installIn(root, "srcFirst", alphaFixture())
        if (first !is PackageInstallResult.Success) {
            return false to "setup install failed: ${describe(first)}"
        }
        val second = installIn(root, "srcSecond", alphaFixture())
        val met = second is PackageInstallResult.TransactionConflict
        return met to "expected TransactionConflict; ${if (met) "refused" else "got " + describe(second)}"
    }

    private fun installedWithoutLiveRejected(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "d_nolive") { r ->
            // An INSTALLED record for "lost" with no live directory (inconsistent).
            PackageStateFile.writeInstalled(
                PackageStateFile.stateFileFor(r, "lost"),
                "1.0", "arm64-v8a", BaseUserspaceFiles.digestHex(ByteArray(0)),
            )
        }
        val result = installIn(root, "src", fixtureOf(
            name = "lost", version = "1.0", payloads = mapOf("payload.txt" to "lost data"),
        ))
        val met = result is PackageInstallResult.InconsistentState &&
            (result as? PackageInstallResult.Failure)?.packageName == "lost"
        return met to "expected InconsistentState for 'lost'; ${if (met) "refused" else "got " + describe(result)}"
    }

    private fun orphanLiveRejected(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "d_orphan") { r ->
            // A live directory for "ghost" with no state record and no staging.
            val live = File(r, "userspace/packages/ghost")
            live.mkdirs()
            writeText(File(live, "manifest"), "orphaned live content, no state")
        }
        val result = installIn(root, "src", fixtureOf(
            name = "ghost", version = "1.0", payloads = mapOf("payload.txt" to "ghost data"),
        ))
        val met = result is PackageInstallResult.InconsistentState &&
            (result as? PackageInstallResult.Failure)?.packageName == "ghost"
        return met to "expected InconsistentState for 'ghost'; ${if (met) "refused" else "got " + describe(result)}"
    }

    // ---- E. Reconciliation cases (A-E) ----

    private fun staleStagingRemoved(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "e_stale")
        val staging = packageStaging(root, "stalealpha")
        writeFixtureInto(staging, alphaFixture()) // no .verified marker: incomplete
        val report = PackageTransaction.reconcile(root)
        val item = report.items.singleOrNull { it.packageName == "stalealpha" }
        val met = report.ok && item?.ok == true && !staging.exists()
        val detail = "items=${report.items}; staging present after reconcile=${staging.exists()}"
        return met to detail
    }

    private fun verifiedStagingRemoved(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "e_verified")
        val staging = packageStaging(root, "spare")
        writeFixtureInto(staging, alphaFixture())
        writeVerified(staging)
        val report = PackageTransaction.reconcile(root)
        val item = report.items.singleOrNull { it.packageName == "spare" }
        val live = File(root, "userspace/packages/spare")
        val met = report.ok && item?.ok == true && !staging.exists() &&
            !live.exists() && !File(root, "metadata/packages/spare.state").exists()
        val detail = "item=${item}; staging gone=${!staging.exists()} liveGone=${!live.exists()} " +
            "no auto-install=${!File(root, "metadata/packages/spare.state").exists()}"
        return met to detail
    }

    private fun interruptedPromotionCompleted(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "e_interrupted")
        // A live tree that still carries the verified marker: the durable evidence
        // of an interrupted post-promotion transaction (no staging, no state).
        val live = packageLive(root, "halfway")
        writeFixtureInto(live, alphaFixture())
        writeVerified(live)
        val report = PackageTransaction.reconcile(root)
        val item = report.items.singleOrNull { it.packageName == "halfway" }
        val record = PackageStateFile.read(PackageStateFile.stateFileFor(root, "halfway"))
        val met = report.ok && item?.ok == true && record?.installed == true &&
            !File(live, PackageLayout.VERIFIED_MARKER_FILE).exists()
        val detail = "item=${item}; installed=${record?.installed}; marker gone=" +
            "${!File(live, PackageLayout.VERIFIED_MARKER_FILE).exists()}"
        return met to detail
    }

    private fun identityMismatchRefused(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "e_mismatch")
        // Verified staging for version 1.0 alongside a live tree for version 2.0
        // with no state: the canonical identities differ, so completion must be
        // refused and neither tree may be touched.
        val staged = packageStaging(root, "split")
        writeFixtureInto(staged, alphaFixture())
        writeVerified(staged)
        val live = packageLive(root, "split")
        writeFixtureInto(live, fixtureOf(
            name = "split", version = "2.0",
            payloads = mapOf("payload.txt" to "version two"),
        ))
        val report = PackageTransaction.reconcile(root)
        val item = report.items.singleOrNull { it.packageName == "split" }
        val met = item?.ok == false && staged.exists() && live.exists() &&
            !File(root, "metadata/packages/split.state").exists()
        val detail = "item=${item}; staging kept=${staged.exists()} live kept=${live.exists()} " +
            "no state=${!File(root, "metadata/packages/split.state").exists()}"
        return met to detail
    }

    private fun installedMissingLiveReported(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "e_d") { r ->
            PackageStateFile.writeInstalled(
                PackageStateFile.stateFileFor(r, "done"),
                "1.0", "arm64-v8a", BaseUserspaceFiles.digestHex(ByteArray(0)),
            )
        }
        val report = PackageTransaction.reconcile(root)
        val item = report.items.singleOrNull { it.packageName == "done" }
        val record = PackageStateFile.read(PackageStateFile.stateFileFor(root, "done"))
        val live = packageLive(root, "done")
        val met = !report.ok && report.inconsistentCount >= 1 && item?.ok == false &&
            record?.installed == true && !live.exists()
        val detail = "item=${item}; report.ok=${report.ok}; state preserved=${record?.installed}; " +
            "no live fabricated=${!live.exists()}"
        return met to detail
    }

    private fun plainOrphanReported(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "e_orphan")
        val live = packageLive(root, "orphan")
        writeFixtureInto(live, fixtureOf(
            name = "orphan", version = "1.0", payloads = mapOf("payload.txt" to "orphan data"),
        )) // no marker, no state, no staging
        val report = PackageTransaction.reconcile(root)
        val item = report.items.singleOrNull { it.packageName == "orphan" }
        val record = PackageStateFile.read(PackageStateFile.stateFileFor(root, "orphan"))
        val met = !report.ok && item?.ok == false && live.exists() && record?.installed != true
        val detail = "item=${item}; live kept=${live.exists()} not auto-marked=" +
            "${record?.installed != true}"
        return met to detail
    }

    private fun leftoverTidied(scratchRoot: File): Pair<Boolean, String> {
        val root = baseRoot(scratchRoot, "e_tidy")
        // A completed install whose cleanup did not finish: INSTALLED state, a
        // live tree still carrying the marker, and leftover verified staging.
        val live = packageLive(root, "tidied")
        writeFixtureInto(live, alphaFixture())
        writeVerified(live)
        val staging = packageStaging(root, "tidied")
        writeFixtureInto(staging, alphaFixture())
        writeVerified(staging)
        PackageStateFile.writeInstalled(
            PackageStateFile.stateFileFor(root, "tidied"),
            "1.0", "arm64-v8a", BaseUserspaceFiles.digestHex(alphaFixture().manifest.canonicalBytes),
        )
        val report = PackageTransaction.reconcile(root)
        val item = report.items.singleOrNull { it.packageName == "tidied" }
        val record = PackageStateFile.read(PackageStateFile.stateFileFor(root, "tidied"))
        val met = report.ok && item?.ok == true && !staging.exists() &&
            !File(live, PackageLayout.VERIFIED_MARKER_FILE).exists() &&
            record?.installed == true && live.exists()
        val detail = "item=${item}; staging gone=${!staging.exists()} " +
            "live marker gone=${!File(live, PackageLayout.VERIFIED_MARKER_FILE).exists()} " +
            "state kept=${record?.installed}"
        return met to detail
    }

    // ---- Fixtures and filesystem helpers ----

    /** A manifest plus the exact payload bytes its declared digests describe. */
    private class Fixture(
        val manifest: PackageManifest,
        val payloads: Map<String, ByteArray>,
    )

    private fun alphaFixture(): Fixture = fixtureOf(
        name = "alpha", version = "1.0",
        payloads = mapOf("payload.txt" to "hello package"),
    )

    private fun digestFixture(): Fixture = fixtureOf(
        name = "digestpkg", version = "1.0",
        payloads = mapOf("payload.txt" to "declared digest content"),
    )

    private fun fixtureOf(
        name: String,
        version: String,
        payloads: Map<String, String>,
        exec: Set<String> = emptySet(),
        depends: List<PackageReference> = emptyList(),
        conflicts: List<PackageReference> = emptyList(),
        replaces: List<PackageReference> = emptyList(),
        provenance: Map<String, String> = emptyMap(),
    ): Fixture {
        val entries = payloads.map { (path, content) ->
            PackageFileEntry(
                path = path,
                sha256 = BaseUserspaceFiles.digestHex(content.toByteArray(Charsets.UTF_8)),
                exec = path in exec,
            )
        }
        val manifest = PackageManifest.create(
            name = name,
            version = version,
            files = entries,
            depends = depends,
            conflicts = conflicts,
            replaces = replaces,
            provenance = provenance,
        )
        return Fixture(manifest, payloads.mapValues { (_, content) ->
            content.toByteArray(Charsets.UTF_8)
        })
    }

    /** Writes [fixture]'s canonical reserved manifest plus its payload files into
     * [dir] (a package source, staging or live tree root). [skip] omits payload
     * paths that the scenario deliberately cannot materialize as files. */
    private fun writeFixtureInto(dir: File, fixture: Fixture, skip: Set<String> = emptySet()) {
        writeBytes(File(dir, PackageLayout.RESERVED_MANIFEST_FILE), fixture.manifest.canonicalBytes)
        for ((path, bytes) in fixture.payloads) {
            if (path in skip) continue
            val target = File(dir, path)
            target.parentFile?.mkdirs()
            writeBytes(target, bytes)
        }
    }

    /** A fresh empty runtime root for one isolated case under [scratchRoot]. */
    private fun baseRoot(
        scratchRoot: File,
        key: String,
        setup: (File) -> Unit = {},
    ): File {
        val dir = File(scratchRoot, key)
        wipe(dir)
        dir.mkdirs()
        val root = File(dir, "root")
        root.mkdirs()
        setup(root)
        return root
    }

    /** Installs [fixture] into [root] from a source dir named [sourceName]. */
    private fun installIn(root: File, sourceName: String, fixture: Fixture): PackageInstallResult {
        val source = File(root, sourceName)
        wipe(source)
        source.mkdirs()
        writeFixtureInto(source, fixture)
        return PackageTransaction.install(root, source)
    }

    /** Builds a fresh root + source and runs one install; [build] fills the
     * source dir exactly as the scenario needs (may deviate from a clean tree). */
    private fun installSource(
        scratchRoot: File,
        key: String,
        sourceName: String = "source",
        build: (File) -> Unit,
    ): Pair<PackageInstallResult, File> {
        val dir = File(scratchRoot, key)
        wipe(dir)
        dir.mkdirs()
        val root = File(dir, "root")
        root.mkdirs()
        val source = File(dir, sourceName)
        source.mkdirs()
        build(source)
        return PackageTransaction.install(root, source) to root
    }

    private fun packageStaging(root: File, name: String): File {
        val dir = File(root, PackageLayout.STAGING_PACKAGES_RELATIVE_DIR)
        dir.mkdirs()
        return File(dir, name).apply { mkdirs() }
    }

    private fun packageLive(root: File, name: String): File {
        val dir = File(root, PackageLayout.PACKAGES_RELATIVE_DIR)
        dir.mkdirs()
        return File(dir, name).apply { mkdirs() }
    }

    private fun writeVerified(dir: File) {
        writeBytes(File(dir, PackageLayout.VERIFIED_MARKER_FILE), ByteArray(0))
    }

    /** Hand-authored canonical-style manifest text for parse-failure scenarios:
     * [formatVersion], [arch] and [fileLine] override the otherwise valid default
     * header body, so the transaction's parse gate fails for exactly the reason
     * the scenario names (unsupported format/arch, unsafe file path). */
    private fun manifestText(
        formatVersion: Int = PackageManifest.FORMAT_VERSION,
        arch: String = PackageManifest.SUPPORTED_ARCH,
        fileLine: String = "file payload.txt ${"b".repeat(64)}",
    ): String = listOf(
        "formatVersion=$formatVersion",
        "name=custom",
        "version=1.0",
        "arch=$arch",
        fileLine,
    ).joinToString("\n")

    private fun describe(result: PackageInstallResult): String = when (result) {
        is PackageInstallResult.Success ->
            "Success(name=${result.name}, version=${result.version}, arch=${result.arch})"
        is PackageInstallResult.Malformed ->
            "Malformed(${result.phase}: ${result.message})"
        is PackageInstallResult.Unsupported ->
            "Unsupported(${result.phase}: ${result.message})"
        is PackageInstallResult.PolicyRejected ->
            "PolicyRejected(${result.phase}: ${result.message})"
        is PackageInstallResult.IntegrityFailed ->
            "IntegrityFailed(${result.phase}: ${result.message})"
        is PackageInstallResult.DependencyFailed ->
            "DependencyFailed(${result.phase}: ${result.message})"
        is PackageInstallResult.ConflictDetected ->
            "ConflictDetected(${result.phase}: ${result.message})"
        is PackageInstallResult.TransactionConflict ->
            "TransactionConflict(${result.phase}: ${result.message})"
        is PackageInstallResult.FilesystemFailed ->
            "FilesystemFailed(${result.phase}: ${result.message})"
        is PackageInstallResult.InconsistentState ->
            "InconsistentState(${result.phase}: ${result.message})"
    }

    /** Builds one case from a label and its `(expectedMet, detail)` assertion. */
    private fun case(label: String, assertion: Pair<Boolean, String>) =
        PackageTransactionSelfCheckCase(label, assertion.first, assertion.second)

    private fun report(vararg cases: PackageTransactionSelfCheckCase): PackageTransactionSelfCheckReport =
        PackageTransactionSelfCheckReport(cases.toList())

    private fun wipe(file: File) {
        if (file.exists()) runCatching { file.deleteRecursively() }
    }

    private fun writeBytes(file: File, bytes: ByteArray) {
        runCatching { file.writeBytes(bytes) }
    }

    private fun writeText(file: File, text: String) {
        writeBytes(file, text.toByteArray(Charsets.UTF_8))
    }
}
