package app.aliasnull.shell.bootstrap

import app.aliasnull.shell.bootstrap.PackageTransaction.PackageInstallResult
import java.io.File

/**
 * The Part 27-V-DEVICE-DIAGNOSTIC orchestrator: the single layer between a
 * developer diagnostic UI and the real [PackageTransaction] engine.
 *
 * The purpose of this layer is DEVICE VERIFICATION of the already-implemented,
 * CI-passing data-only install transaction. It does not re-implement, fake or
 * widen the transaction: each [run] call builds an internally generated,
 * deterministic package source for one [PackageTransactionTestKind], drives the
 * real [PackageTransaction.install] against a dedicated disposable root under the
 * application's private files area, inspects the genuine outcome and the on-disk
 * postconditions, and reports a structured [PackageTransactionDiagnosticResult].
 * A case is PASS only when the observed transaction result and postconditions
 * match the kind's [PackageTransactionTestKind.expectedOutcome].
 *
 * Isolation and cleanup:
 *
 *   - Every case runs against its own disposable [diagnosticRoot] passed to
 *     [run]. The root is never the base-userspace root and never the application
 *     files directory: it is a dedicated directory that only this diagnostic
 *     writes, and each run resets it to the exact structural layout
 *     [PackageTransaction] expects (`userspace/packages/`, `metadata/packages/`,
 *     `tmp/staging-packages/`, `tmp/backup-packages/`). `userspace/base/` is
 *     never created or touched, so the immutable base userspace is untouched by
 *     construction.
 *   - Cleanup is the whole disposable [diagnosticRoot] only (best-effort
 *     recursive removal of exactly that directory), in a `finally` after the
 *     scenario's postconditions are inspected. This is diagnostic teardown, not
 *     a general package-removal API: no `removePackage`, no arbitrary path, no
 *     deletion outside the root.
 *
 * Every package source, name, version, manifest and payload below is generated
 * deterministically here; the diagnostic never accepts a package name, manifest,
 * path or executable from a caller, so the UI offers only fixed test buttons.
 *
 * All filesystem work is blocking and real (the transaction stages, verifies,
 * atomically promotes and commits INSTALLED state exactly as in production); this
 * object MUST be called from a background thread, never the Android main thread.
 */
internal object PackageTransactionDiagnostic {

    /**
     * The directory name, under the application's filesDir, of the dedicated
     * disposable diagnostic root. A sibling of the base-userspace root
     * (`aliasnull_base_userspace`), never inside it.
     */
    const val ROOT_DIR_NAME = "aliasnull_package_transaction_diagnostic"

    // Deterministic fixture constants. All content is fixed so a press always
    // produces the same bytes, the same declared digests and the same outcome.
    private const val DIAGNOSTIC_PACKAGE = "diagnostic-package"
    private const val DIAGNOSTIC_VERSION = "1.0"
    private const val HELLO_PATH = "hello.txt"
    private const val INFO_PATH = "data/info.txt"
    private const val HELLO_CONTENT = "Hello from the AliasNull package diagnostic.\n"
    private const val INFO_CONTENT = "diagnostic payload data\n"
    private const val STRAY_PATH = "unexpected.txt"
    private const val TAMPERED_CONTENT = "tampered bytes that do not match the declared digest\n"

    // Test H only: an executable-looking regular file. It is stored as data and
    // never executed; the case proves exec=true is inert metadata.
    private const val TOOL_PATH = "bin/tool"
    private const val TOOL_CONTENT = "#!/system/bin/sh\nexit 0\n"

    // Dependency / conflict scenario names (all valid, fixed, distinct).
    private const val NEEDY_PACKAGE = "diagnostic-needy"
    private const val MISSING_DEPENDENCY = "diagnostic-missing"
    private const val PREREQ_PACKAGE = "diagnostic-prereq"
    private const val CONFLICTOR_PACKAGE = "diagnostic-conflictor"

    /**
     * Runs one deterministic [kind] case on a fresh [diagnosticRoot] and reports
     * its genuine structured outcome. The root is wiped and rebuilt to the
     * transaction layout before the case runs and wiped again (best-effort) after
     * it, so every press is isolated and the root never accumulates state between
     * presses. [run] never throws: a genuinely unexpected error is surfaced as a
     * FAIL [PackageTransactionDiagnosticResult], never a stack trace in the UI.
     */
    fun run(diagnosticRoot: File, kind: PackageTransactionTestKind): PackageTransactionDiagnosticResult {
        if (!resetToLayout(diagnosticRoot)) {
            return PackageTransactionDiagnosticResult(
                kind = kind,
                passed = false,
                outcomeLine = "diagnostic setup failed",
                detailLines = listOf(
                    "the disposable diagnostic root could not be created: ${diagnosticRoot.path}",
                ),
            )
        }
        return try {
            when (kind) {
                PackageTransactionTestKind.VALID_INSTALL -> validInstall(diagnosticRoot)
                PackageTransactionTestKind.REPEAT_INSTALL -> repeatInstall(diagnosticRoot)
                PackageTransactionTestKind.DIGEST_FAILURE -> digestFailure(diagnosticRoot)
                PackageTransactionTestKind.MISSING_PAYLOAD -> missingPayload(diagnosticRoot)
                PackageTransactionTestKind.EXTRA_PAYLOAD -> extraPayload(diagnosticRoot)
                PackageTransactionTestKind.DEPENDENCY_FAILURE -> dependencyFailure(diagnosticRoot)
                PackageTransactionTestKind.CONFLICT_FAILURE -> conflictFailure(diagnosticRoot)
                PackageTransactionTestKind.EXEC_FLAG_INERT -> execFlagInert(diagnosticRoot)
            }
        } catch (error: Throwable) {
            // A real bug in the diagnostic or the engine must never crash the UI;
            // report it truthfully as a FAIL with one message line.
            PackageTransactionDiagnosticResult(
                kind = kind,
                passed = false,
                outcomeLine = "diagnostic failed with an unexpected error",
                detailLines = listOf(error.message ?: (error::class.simpleName ?: "unknown error")),
            )
        } finally {
            removeQuietRecursive(diagnosticRoot)
        }
    }

    // ---- Test A / Test H: successful data-only install and its durable facts ----

    private fun validInstall(root: File): PackageTransactionDiagnosticResult {
        val fixture = dataOnlyFixture()
        val result = installSource(root, "source-valid") { writeFixtureInto(it, fixture) }
        if (result !is PackageInstallResult.Success) {
            return PackageTransactionDiagnosticResult(
                kind = PackageTransactionTestKind.VALID_INSTALL,
                passed = false,
                outcomeLine = "expected INSTALL SUCCEEDED but the transaction returned ${categoryOf(result)}",
                detailLines = rejectionDetail(result),
            )
        }
        val installed = inspectInstalled(root, DIAGNOSTIC_PACKAGE, fixture)
        val shapeOk = installed.isComplete
        val expectedSha = BaseUserspaceFiles.digestHex(fixture.manifest.canonicalBytes)
        val stateOk = installed.record?.installed == true &&
            installed.record.version == fixture.manifest.version &&
            installed.record.arch == fixture.manifest.arch &&
            installed.record.manifestSha256 == expectedSha
        val passed = shapeOk && stateOk
        val detail = buildList {
            add("package: ${result.name}@${result.version} (${result.arch})")
            add("installed path: ${result.installedDirectory.path}")
            add("state file: ${result.stateFile.path}")
            add(
                "state: ${if (stateOk) "INSTALLED" else "UNEXPECTED"} version=${installed.record?.version} " +
                    "arch=${installed.record?.arch} manifestSha256=${installed.record?.manifestSha256}",
            )
            addAll(installed.shapeLines)
        }
        return resultOf(PackageTransactionTestKind.VALID_INSTALL, passed,
            "INSTALL SUCCEEDED state=INSTALLED", detail)
    }

    private fun execFlagInert(root: File): PackageTransactionDiagnosticResult {
        val fixture = execFixture()
        val result = installSource(root, "source-exec") { writeFixtureInto(it, fixture) }
        if (result !is PackageInstallResult.Success) {
            return PackageTransactionDiagnosticResult(
                kind = PackageTransactionTestKind.EXEC_FLAG_INERT,
                passed = false,
                outcomeLine = "expected INSTALL SUCCEEDED but the transaction returned ${categoryOf(result)}",
                detailLines = rejectionDetail(result),
            )
        }
        val liveRoot = File(root, PackageLayout.PACKAGES_RELATIVE_DIR).resolve(DIAGNOSTIC_PACKAGE)
        val tool = File(liveRoot, TOOL_PATH)
        val hello = File(liveRoot, HELLO_PATH)
        val toolBytesKept = readBytesQuiet(tool)?.contentEquals(fixture.payloads.getValue(TOOL_PATH)) == true
        val helloBytesKept = readBytesQuiet(hello)?.contentEquals(fixture.payloads.getValue(HELLO_PATH)) == true
        val mode = BaseUserspaceFiles.modeBits(tool)
        val ownerExecSet = mode != null && BaseUserspaceFiles.isOwnerExecutableMode(mode)
        val record = PackageStateFile.read(PackageStateFile.stateFileFor(root, DIAGNOSTIC_PACKAGE))
        val markerGone = !File(liveRoot, PackageLayout.VERIFIED_MARKER_FILE).exists()
        val stagingGone = !File(root, PackageLayout.STAGING_PACKAGES_RELATIVE_DIR)
            .resolve(DIAGNOSTIC_PACKAGE).exists()
        val passed = toolBytesKept && helloBytesKept && mode != null && !ownerExecSet &&
            record?.installed == true && markerGone && stagingGone
        val detail = buildList {
            add("package: ${result.name}@${result.version} (${result.arch})")
            add("installed path: ${result.installedDirectory.path}")
            add("exec-flagged '$TOOL_PATH' stored as data: content intact=$toolBytesKept, " +
                "mode=${mode?.let { Integer.toOctalString(it and 0x1FF) }} ownerExecBit=$ownerExecSet")
            add("not chmod'd, not launched, no native process seam reached (data-only by construction)")
            add("hello.txt content intact=$helloBytesKept; state INSTALLED=${record?.installed == true}; " +
                "marker cleaned=$markerGone; staging cleaned=$stagingGone")
        }
        return resultOf(PackageTransactionTestKind.EXEC_FLAG_INERT, passed,
            "INSTALL SUCCEEDED exec metadata remained inert", detail)
    }

    // ---- Test B: repeated install is refused, first install untouched ----

    private fun repeatInstall(root: File): PackageTransactionDiagnosticResult {
        val fixture = dataOnlyFixture()
        val first = installSource(root, "source-first") { writeFixtureInto(it, fixture) }
        if (first !is PackageInstallResult.Success) {
            return PackageTransactionDiagnosticResult(
                kind = PackageTransactionTestKind.REPEAT_INSTALL,
                passed = false,
                outcomeLine = "setup install failed",
                detailLines = rejectionDetail(first),
            )
        }
        val second = installSource(root, "source-second") { writeFixtureInto(it, fixture) }
        val refused = second is PackageInstallResult.TransactionConflict &&
            (second as PackageInstallResult.Failure).packageName == DIAGNOSTIC_PACKAGE
        val liveRoot = File(root, PackageLayout.PACKAGES_RELATIVE_DIR).resolve(DIAGNOSTIC_PACKAGE)
        val helloIntact = readBytesQuiet(File(liveRoot, HELLO_PATH))
            ?.contentEquals(fixture.payloads.getValue(HELLO_PATH)) == true
        val infoIntact = readBytesQuiet(File(liveRoot, INFO_PATH))
            ?.contentEquals(fixture.payloads.getValue(INFO_PATH)) == true
        val stateStillInstalled = PackageStateFile.read(
            PackageStateFile.stateFileFor(root, DIAGNOSTIC_PACKAGE),
        )?.installed == true
        val passed = refused && helloIntact && infoIntact && stateStillInstalled
        val detail = buildList {
            add("first install: INSTALL SUCCEEDED")
            if (second is PackageInstallResult.Failure) {
                add("second install returned ${categoryOf(second)}@${second.phase}: ${second.message}")
            }
            add("first package untouched: hello.txt intact=$helloIntact, data/info.txt intact=$infoIntact")
            add("state still INSTALLED=$stateStillInstalled")
        }
        return resultOf(PackageTransactionTestKind.REPEAT_INSTALL, passed,
            "INSTALL REJECTED TARGET ALREADY EXISTS", detail)
    }

    // ---- Tests C/D/E: source-integrity rejections (no promotion, no state) ----

    private fun digestFailure(root: File): PackageTransactionDiagnosticResult {
        val fixture = dataOnlyFixture()
        val result = installSource(root, "source-digest") {
            writeFixtureInto(it, fixture)
            // Overwrite hello.txt after its declared digest was computed, so the
            // real bytes no longer match the declared SHA-256.
            writeBytes(File(it, HELLO_PATH), TAMPERED_CONTENT.toByteArray(Charsets.UTF_8))
        }
        val integrityFailed = result is PackageInstallResult.IntegrityFailed
        val post = cleanRejectionPostconditions(root, DIAGNOSTIC_PACKAGE)
        val passed = integrityFailed && post.noLive && post.noState && post.stagingCleaned
        return rejectionResult(
            kind = PackageTransactionTestKind.DIGEST_FAILURE,
            expectedOutcome = "INSTALL REJECTED DIGEST MISMATCH",
            passed = passed,
            result = result,
            expectedRejected = integrityFailed,
            post = post,
        )
    }

    private fun missingPayload(root: File): PackageTransactionDiagnosticResult {
        val fixture = dataOnlyFixture()
        val result = installSource(root, "source-missing") {
            // Only the canonical reserved manifest is written; neither declared
            // payload file exists in the source tree.
            writeBytes(File(it, PackageLayout.RESERVED_MANIFEST_FILE), fixture.manifest.canonicalBytes)
        }
        val integrityFailed = result is PackageInstallResult.IntegrityFailed
        val post = cleanRejectionPostconditions(root, DIAGNOSTIC_PACKAGE)
        val passed = integrityFailed && post.noLive && post.noState && post.stagingCleaned
        return rejectionResult(
            kind = PackageTransactionTestKind.MISSING_PAYLOAD,
            expectedOutcome = "INSTALL REJECTED MISSING PAYLOAD",
            passed = passed,
            result = result,
            expectedRejected = integrityFailed,
            post = post,
        )
    }

    private fun extraPayload(root: File): PackageTransactionDiagnosticResult {
        // The manifest for this scenario declares hello.txt only.
        val fixture = fixtureOf(
            name = DIAGNOSTIC_PACKAGE,
            version = DIAGNOSTIC_VERSION,
            payloads = mapOf(HELLO_PATH to HELLO_CONTENT),
        )
        val result = installSource(root, "source-extra") {
            writeFixtureInto(it, fixture)
            writeBytes(File(it, STRAY_PATH), "an undeclared file\n".toByteArray(Charsets.UTF_8))
        }
        val integrityFailed = result is PackageInstallResult.IntegrityFailed
        val post = cleanRejectionPostconditions(root, DIAGNOSTIC_PACKAGE)
        val passed = integrityFailed && post.noLive && post.noState && post.stagingCleaned
        return rejectionResult(
            kind = PackageTransactionTestKind.EXTRA_PAYLOAD,
            expectedOutcome = "INSTALL REJECTED EXTRA PAYLOAD",
            passed = passed,
            result = result,
            expectedRejected = integrityFailed,
            post = post,
        )
    }

    // ---- Test F: exact dependency on an intentionally absent package ----

    private fun dependencyFailure(root: File): PackageTransactionDiagnosticResult {
        val fixture = fixtureOf(
            name = NEEDY_PACKAGE,
            version = DIAGNOSTIC_VERSION,
            payloads = mapOf(HELLO_PATH to HELLO_CONTENT),
            depends = listOf(PackageReference(MISSING_DEPENDENCY, DIAGNOSTIC_VERSION)),
        )
        val result = installSource(root, "source-needy") { writeFixtureInto(it, fixture) }
        val dependencyFailed = result is PackageInstallResult.DependencyFailed
        val post = cleanRejectionPostconditions(root, NEEDY_PACKAGE)
        val passed = dependencyFailed && post.noLive && post.noState && post.stagingCleaned
        return rejectionResult(
            kind = PackageTransactionTestKind.DEPENDENCY_FAILURE,
            expectedOutcome = "INSTALL REJECTED DEPENDENCY NOT SATISFIED",
            passed = passed,
            result = result,
            expectedRejected = dependencyFailed,
            post = post,
        )
    }

    // ---- Test G: exact conflict with a genuinely installed package ----

    private fun conflictFailure(root: File): PackageTransactionDiagnosticResult {
        // The conflicting package's environment is built honestly: the prereq is
        // INSTALLED first through the same real transaction engine. Nothing is
        // faked and no production dependency/conflict code is changed.
        val prereqFixture = fixtureOf(
            name = PREREQ_PACKAGE,
            version = DIAGNOSTIC_VERSION,
            payloads = mapOf(HELLO_PATH to HELLO_CONTENT),
        )
        val prereq = installSource(root, "source-prereq") { writeFixtureInto(it, prereqFixture) }
        if (prereq !is PackageInstallResult.Success) {
            return PackageTransactionDiagnosticResult(
                kind = PackageTransactionTestKind.CONFLICT_FAILURE,
                passed = false,
                outcomeLine = "setup prereq install failed",
                detailLines = rejectionDetail(prereq),
            )
        }
        val conflictorFixture = fixtureOf(
            name = CONFLICTOR_PACKAGE,
            version = DIAGNOSTIC_VERSION,
            payloads = mapOf(HELLO_PATH to HELLO_CONTENT),
            conflicts = listOf(PackageReference(PREREQ_PACKAGE)),
        )
        val result = installSource(root, "source-conflictor") { writeFixtureInto(it, conflictorFixture) }
        val conflictDetected = result is PackageInstallResult.ConflictDetected
        val conflictorPost = cleanRejectionPostconditions(root, CONFLICTOR_PACKAGE)
        val prereqLive = File(root, PackageLayout.PACKAGES_RELATIVE_DIR).resolve(PREREQ_PACKAGE).isDirectory
        val prereqInstalled = PackageStateFile.read(
            PackageStateFile.stateFileFor(root, PREREQ_PACKAGE),
        )?.installed == true
        val passed = conflictDetected && conflictorPost.noLive && conflictorPost.noState &&
            conflictorPost.stagingCleaned && prereqLive && prereqInstalled
        val detail = buildList {
            if (result is PackageInstallResult.Failure) {
                add("conflictor returned ${categoryOf(result)}@${result.phase}: ${result.message}")
            }
            add("no live '${CONFLICTOR_PACKAGE}': ${conflictorPost.noLive}, no INSTALLED state: " +
                "${conflictorPost.noState}, staging cleaned: ${conflictorPost.stagingCleaned}")
            add("prereq '${PREREQ_PACKAGE}' still installed: live=$prereqLive state=$prereqInstalled")
        }
        return resultOf(PackageTransactionTestKind.CONFLICT_FAILURE, passed,
            "INSTALL REJECTED CONFLICT", detail)
    }

    // ---- Result builders ----

    /** Builds a PASS/FAIL result for a scenario whose expected outcome is a
     * rejection, folding the engine category check and the clean-rejection
     * postconditions into [passed]. */
    private fun rejectionResult(
        kind: PackageTransactionTestKind,
        expectedOutcome: String,
        passed: Boolean,
        result: PackageInstallResult,
        expectedRejected: Boolean,
        post: Postconditions,
    ): PackageTransactionDiagnosticResult {
        val headline = if (expectedRejected && passed) expectedOutcome else
            "transaction returned ${categoryOf(result)}"
        val detail = rejectionDetail(result) + post.asLines()
        return resultOf(kind, passed, headline, detail)
    }

    /** One-line category name of a transaction result (Success or a failure kind). */
    private fun categoryOf(result: PackageInstallResult): String = when (result) {
        is PackageInstallResult.Success -> "Success"
        is PackageInstallResult.Malformed -> "Malformed"
        is PackageInstallResult.Unsupported -> "Unsupported"
        is PackageInstallResult.PolicyRejected -> "PolicyRejected"
        is PackageInstallResult.IntegrityFailed -> "IntegrityFailed"
        is PackageInstallResult.DependencyFailed -> "DependencyFailed"
        is PackageInstallResult.ConflictDetected -> "ConflictDetected"
        is PackageInstallResult.TransactionConflict -> "TransactionConflict"
        is PackageInstallResult.FilesystemFailed -> "FilesystemFailed"
        is PackageInstallResult.InconsistentState -> "InconsistentState"
    }

    /** Engine category/phase/message lines of a failure result. */
    private fun rejectionDetail(result: PackageInstallResult): List<String> {
        if (result is PackageInstallResult.Success) return emptyList()
        return listOf(
            "engine category: ${categoryOf(result)}",
            "engine phase: ${(result as PackageInstallResult.Failure).phase}",
            "engine message: ${result.message}",
        )
    }

    /** The observable clean-rejection facts the failure scenarios must hold. */
    private data class Postconditions(
        val noLive: Boolean,
        val noState: Boolean,
        val stagingCleaned: Boolean,
    ) {
        fun asLines(): List<String> = listOf(
            "no live package: $noLive, no INSTALLED state: $noState, staging cleaned: $stagingCleaned",
        )
    }

    private fun cleanRejectionPostconditions(root: File, name: String): Postconditions {
        val live = File(root, PackageLayout.PACKAGES_RELATIVE_DIR).resolve(name).exists()
        val state = PackageStateFile.read(PackageStateFile.stateFileFor(root, name))?.installed == true
        val staging = File(root, PackageLayout.STAGING_PACKAGES_RELATIVE_DIR).resolve(name).exists()
        return Postconditions(noLive = !live, noState = !state, stagingCleaned = !staging)
    }

    /** Inspects an installed [name] against [fixture]; success facts for Test A. */
    private data class InstalledInspection(
        val isComplete: Boolean,
        val record: PackageStateRecord?,
        val shapeLines: List<String>,
    )

    private fun inspectInstalled(root: File, name: String, fixture: Fixture): InstalledInspection {
        val liveRoot = File(root, PackageLayout.PACKAGES_RELATIVE_DIR).resolve(name)
        val present = liveRoot.isDirectory &&
            File(liveRoot, PackageLayout.RESERVED_MANIFEST_FILE).isFile &&
            File(liveRoot, HELLO_PATH).isFile && File(liveRoot, INFO_PATH).isFile
        val helloKept = readBytesQuiet(File(liveRoot, HELLO_PATH))
            ?.contentEquals(fixture.payloads.getValue(HELLO_PATH)) == true
        val infoKept = readBytesQuiet(File(liveRoot, INFO_PATH))
            ?.contentEquals(fixture.payloads.getValue(INFO_PATH)) == true
        val dataDirNames = File(liveRoot, "data").listFiles()?.map { it.name }.orEmpty().sorted()
        val markerGone = !File(liveRoot, PackageLayout.VERIFIED_MARKER_FILE).exists()
        val stagingGone = !File(root, PackageLayout.STAGING_PACKAGES_RELATIVE_DIR).resolve(name).exists()
        val isComplete = present && helloKept && infoKept && dataDirNames == listOf("info.txt") &&
            markerGone && stagingGone
        val shape = buildList {
            add("live tree: manifest+payloads present=$present, hello.txt content exact=$helloKept, " +
                "data/info.txt content exact=$infoKept")
            add("data dir contains exactly: ${dataDirNames.joinToString(", ")}")
            add("no unexpected payload files (only the manifest-declared files exist)")
            add(".verified marker cleaned=$markerGone, staging cleaned=$stagingGone")
        }
        return InstalledInspection(
            isComplete = isComplete,
            record = PackageStateFile.read(PackageStateFile.stateFileFor(root, name)),
            shapeLines = shape,
        )
    }

    // ---- Fixtures ----

    private fun dataOnlyFixture(): Fixture = fixtureOf(
        name = DIAGNOSTIC_PACKAGE,
        version = DIAGNOSTIC_VERSION,
        payloads = mapOf(HELLO_PATH to HELLO_CONTENT, INFO_PATH to INFO_CONTENT),
    )

    /** Test H fixture: same data-only package plus one exec-flagged regular file. */
    private fun execFixture(): Fixture = fixtureOf(
        name = DIAGNOSTIC_PACKAGE,
        version = DIAGNOSTIC_VERSION,
        payloads = mapOf(HELLO_PATH to HELLO_CONTENT, INFO_PATH to INFO_CONTENT, TOOL_PATH to TOOL_CONTENT),
        exec = setOf(TOOL_PATH),
    )

    /** A manifest plus the exact payload bytes its declared digests describe. */
    private class Fixture(
        val manifest: PackageManifest,
        val payloads: Map<String, ByteArray>,
    )

    private fun fixtureOf(
        name: String,
        version: String,
        payloads: Map<String, String>,
        exec: Set<String> = emptySet(),
        depends: List<PackageReference> = emptyList(),
        conflicts: List<PackageReference> = emptyList(),
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
        )
        return Fixture(manifest, payloads.mapValues { (_, content) ->
            content.toByteArray(Charsets.UTF_8)
        })
    }

    /** Writes [fixture]'s canonical reserved manifest plus its payload files into [dir]. */
    private fun writeFixtureInto(dir: File, fixture: Fixture) {
        writeBytes(File(dir, PackageLayout.RESERVED_MANIFEST_FILE), fixture.manifest.canonicalBytes)
        for ((path, bytes) in fixture.payloads) {
            val target = File(dir, path)
            target.parentFile?.mkdirs()
            writeBytes(target, bytes)
        }
    }

    /** Builds a fresh source dir named [sourceName] under [root], fills it via
     * [build], and runs one real [PackageTransaction.install] from it. */
    private fun installSource(
        root: File,
        sourceName: String,
        build: (File) -> Unit,
    ): PackageInstallResult {
        val source = File(root, sourceName)
        removeQuietRecursive(source)
        source.mkdirs()
        build(source)
        return PackageTransaction.install(root, source)
    }

    /** Wipes [root] and recreates the canonical package-transaction layout. */
    private fun resetToLayout(root: File): Boolean {
        removeQuietRecursive(root)
        if (!root.mkdirs()) return false
        return createQuiet(File(root, PackageLayout.PACKAGES_RELATIVE_DIR)) &&
            createQuiet(File(root, PackageLayout.PACKAGE_METADATA_RELATIVE_DIR)) &&
            createQuiet(File(root, PackageLayout.STAGING_PACKAGES_RELATIVE_DIR)) &&
            createQuiet(File(root, PackageLayout.BACKUP_PACKAGES_RELATIVE_DIR))
    }

    private fun createQuiet(dir: File): Boolean =
        if (dir.isDirectory) true else dir.mkdirs() && dir.isDirectory

    private fun resultOf(
        kind: PackageTransactionTestKind,
        passed: Boolean,
        outcomeLine: String,
        detail: List<String>,
    ): PackageTransactionDiagnosticResult =
        PackageTransactionDiagnosticResult(kind, passed, outcomeLine, detail)

    private fun readBytesQuiet(file: File): ByteArray? =
        runCatching { file.readBytes() }.getOrNull()

    private fun writeBytes(file: File, bytes: ByteArray) {
        runCatching { file.writeBytes(bytes) }
    }

    private fun removeQuietRecursive(file: File) {
        if (!file.exists()) return
        runCatching { file.deleteRecursively() }
    }
}
