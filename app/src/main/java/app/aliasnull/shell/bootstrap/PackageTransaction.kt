package app.aliasnull.shell.bootstrap

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The first real data-only package install transaction (Part 27-V-IMPLEMENTATION).
 *
 * ONE package at a time, INSTALL ONLY. The source is an already-available LOCAL
 * DIRECTORY TREE (never a network/archive/SAF source): a directory containing the
 * reserved [PackageLayout.RESERVED_MANIFEST_FILE] plus the payload regular files
 * the manifest describes. The transaction copies and verifies package DATA; it
 * never executes, never chmods, never ELF-validates, never applies lifecycle
 * hooks, and never routes anything through the native process seam. Package state
 * lives under the same runtime root [BaseUserspaceBootstrap] owns
 * (`userspace/packages/<name>/`, `metadata/packages/<name>.state`,
 * `tmp/staging-packages/<name>/`), on the same filesystem so promotion is a real
 * atomic directory rename; [PackageLayout] is the sole path authority and the
 * manifest model ([PackageManifest]) the sole format authority.
 *
 * Transaction order (the milestone's PHASE A-G contract):
 *
 *   A SOURCE VALIDATION   parse + validate the manifest, then validate the source
 *                         tree exactly against it (files exist, are regular, have
 *                         matching digests, no unexpected object, no reserved-name
 *                         or structural collision, no traversal);
 *   B TARGET CHECK        reject when the target already exists (live directory or
 *                         an INSTALLED state) or when the package is in an
 *                         inconsistent state, and reconcile any stale staging for
 *                         the same package before re-staging;
 *   C STAGING             create `tmp/staging-packages/<name>/`, write the canonical
 *                         manifest, copy each payload file while streaming its
 *                         SHA-256 and compare it to the declared digest;
 *   D STAGED VERIFICATION re-validate the fully staged tree against the manifest;
 *   E VERIFIED MARKER     write `.verified` only after D succeeds (the durable
 *                         "staging verification completed" fact);
 *   F FINAL CHECK         dependency and conflict checks against installed
 *                         packages, then a final live-target-absent re-check;
 *   G PROMOTION           atomic same-filesystem rename of the verified staging
 *                         directory to `userspace/packages/<name>`, followed by
 *                         full live-tree verification and the INSTALLED state
 *                         commit, then cleanup of the transaction marker.
 *
 * The `.verified` marker is transaction metadata, never payload, and is promoted
 * along with the directory so a crash between promotion and the state commit
 * leaves the durable fact (a live tree carrying the marker, fully re-verifiable)
 * that [reconcile] uses to complete the interrupted transaction - recovery never
 * auto-installs arbitrary staging content and only commits INSTALLED after the
 * live tree's canonical manifest identity and every payload digest verify.
 *
 * State (`metadata/packages/<name>.state`) is written ONLY after the promoted
 * live tree has fully verified, using the atomic small-file commit of
 * [PackageStateFile.writeInstalled]. A failed transaction can never claim
 * INSTALLED, the live base userspace is never touched, and each package owns its
 * own structurally disjoint subtree, so cross-package collision is impossible.
 *
 * This object is deliberately dormant - it is not wired to any Shell command, UI
 * or startup path, and nothing auto-installs; startup may call [reconcile] only.
 * All filesystem work is blocking; call from a background thread.
 */
internal object PackageTransaction {

    /** The phase of the milestone contract a failure occurred in. */
    internal enum class Phase {
        SOURCE_VALIDATION,
        TARGET_CHECK,
        STAGING,
        STAGED_VERIFICATION,
        MARKER_WRITE,
        DEPENDENCY_CHECK,
        FINAL_TARGET_CHECK,
        PROMOTION,
        LIVE_VERIFICATION,
        STATE_COMMIT,
        CLEANUP,
    }

    /**
     * The structured outcome of one [install]. [PackageInstallResult.Success]
     * means the package is genuinely installed (live tree verified, INSTALLED
     * state committed). Every other subtype is a distinct failure category per
     * the milestone: malformed / unsupported / policy rejection / integrity /
     * dependency / conflict / transaction conflict / filesystem / inconsistent
     * state. Internal only - not a user-facing CLI result.
     */
    sealed interface PackageInstallResult {
        /** The failure categories; [packageName] is null when the manifest could
         * not be parsed. */
        sealed interface Failure : PackageInstallResult {
            val packageName: String?
            val phase: Phase
            val message: String
        }

        data class Success(
            val name: String,
            val version: String,
            val arch: String,
            val manifestSha256: String,
            val installedDirectory: File,
            val stateFile: File,
        ) : PackageInstallResult

        data class Malformed(
            override val packageName: String?,
            override val phase: Phase,
            override val message: String,
        ) : Failure

        data class Unsupported(
            override val packageName: String?,
            override val phase: Phase,
            override val message: String,
        ) : Failure

        data class PolicyRejected(
            override val packageName: String?,
            override val phase: Phase,
            override val message: String,
        ) : Failure

        data class IntegrityFailed(
            override val packageName: String?,
            override val phase: Phase,
            override val message: String,
        ) : Failure

        data class DependencyFailed(
            override val packageName: String?,
            override val phase: Phase,
            override val message: String,
        ) : Failure

        data class ConflictDetected(
            override val packageName: String?,
            override val phase: Phase,
            override val message: String,
        ) : Failure

        data class TransactionConflict(
            override val packageName: String?,
            override val phase: Phase,
            override val message: String,
        ) : Failure

        data class FilesystemFailed(
            override val packageName: String?,
            override val phase: Phase,
            override val message: String,
        ) : Failure

        data class InconsistentState(
            override val packageName: String?,
            override val phase: Phase,
            override val message: String,
        ) : Failure
    }

    /** One observed package in [reconcile], the action taken, and its outcome. */
    data class ReconcileItem(
        val packageName: String,
        val observed: String,
        val action: String,
        val ok: Boolean,
    )

    /** The aggregated outcome of one [reconcile] run over all package state. */
    data class ReconcileReport(val items: List<ReconcileItem>) {
        val ok: Boolean
            get() = items.all { it.ok }

        val inconsistentCount: Int
            get() = items.count { !it.ok }
    }

    // POSIX type masks mirrored from BaseUserspaceFiles (kept private here).
    private const val S_IFMT = 0xF000
    private const val S_IFREG = 0x8000
    private const val S_IFDIR = 0x4000
    private const val S_IFLNK = 0xA000
    private const val READ_CHUNK_BYTES = 65536

    // ---- Entry points ----

    /**
     * Runs one data-only install of the local package at [sourceDirectory] under
     * the runtime [root]. Never modifies [sourceDirectory] and never touches the
     * base userspace. See the object header for the exact PHASE A-G order.
     */
    fun install(root: File, sourceDirectory: File): PackageInstallResult {
        // ---- PHASE A: source validation ----
        val sourceType = BaseUserspaceFiles.modeBits(sourceDirectory)
        if (sourceType == null) {
            return failure(PackageInstallResult.FilesystemFailed(null, Phase.SOURCE_VALIDATION,
                "the package source does not exist: ${sourceDirectory.path}"))
        }
        when (sourceType and S_IFMT) {
            S_IFLNK -> return failure(PackageInstallResult.PolicyRejected(null, Phase.SOURCE_VALIDATION,
                "the package source is a symbolic link and is not accepted"))
            S_IFDIR -> Unit
            else -> return failure(PackageInstallResult.PolicyRejected(null, Phase.SOURCE_VALIDATION,
                "the package source is not a directory"))
        }
        val manifestFile = File(sourceDirectory, PackageLayout.RESERVED_MANIFEST_FILE)
        val manifestMode = BaseUserspaceFiles.modeBits(manifestFile)
        if (manifestMode == null) {
            return failure(PackageInstallResult.Malformed(null, Phase.SOURCE_VALIDATION,
                "the package source has no reserved 'manifest' file"))
        }
        when (manifestMode and S_IFMT) {
            S_IFLNK -> return failure(PackageInstallResult.PolicyRejected(null, Phase.SOURCE_VALIDATION,
                "the reserved 'manifest' is a symbolic link and is not accepted"))
            S_IFREG -> Unit
            else -> return failure(PackageInstallResult.Malformed(null, Phase.SOURCE_VALIDATION,
                "the reserved 'manifest' is not a regular file"))
        }
        val manifestBytes = PackageTreeValidator.readBytesQuiet(manifestFile)
            ?: return failure(PackageInstallResult.FilesystemFailed(null, Phase.SOURCE_VALIDATION,
                "the reserved 'manifest' could not be read"))
        val manifest = try {
            PackageManifest.parse(manifestBytes.toString(Charsets.UTF_8))
        } catch (error: PackageManifestException) {
            val message = error.message.orEmpty()
            return if (
                message.contains("unsupported manifest format version") ||
                message.contains("unsupported package architecture")
            ) {
                failure(PackageInstallResult.Unsupported(null, Phase.SOURCE_VALIDATION, message))
            } else {
                failure(PackageInstallResult.Malformed(null, Phase.SOURCE_VALIDATION, message))
            }
        }
        val name = manifest.name

        val sourceCheck = PackageTreeValidator.validate(sourceDirectory, manifest, allowVerifiedMarker = false)
        if (!sourceCheck.manifestPresent || !sourceCheck.manifestIsRegularFile ||
            !sourceCheck.manifestParses || !sourceCheck.manifestIdentityMatches
        ) {
            return failure(PackageInstallResult.Malformed(name, Phase.SOURCE_VALIDATION,
                "the source reserved 'manifest' is not the canonical encoding of the parsed manifest"))
        }
        if (sourceCheck.structuralErrors.isNotEmpty() || sourceCheck.unsafePaths.isNotEmpty() ||
            sourceCheck.typeErrors.isNotEmpty()
        ) {
            return failure(PackageInstallResult.PolicyRejected(name, Phase.SOURCE_VALIDATION,
                describeTreeRejection(name, sourceCheck)))
        }
        if (sourceCheck.missingFiles.isNotEmpty() || sourceCheck.unexpectedObjects.isNotEmpty() ||
            sourceCheck.digestMismatches.isNotEmpty()
        ) {
            return failure(PackageInstallResult.IntegrityFailed(name, Phase.SOURCE_VALIDATION,
                describeTreeRejection(name, sourceCheck)))
        }

        // ---- PHASE B: early target/state check ----
        val liveDir = packageLiveDir(root, name)
        val stateFile = PackageStateFile.stateFileFor(root, name)
        val stateRecord = PackageStateFile.read(stateFile)
        val liveExists = liveDir.exists()
        when {
            stateRecord?.installed == true && !liveExists ->
                return failure(PackageInstallResult.InconsistentState(name, Phase.TARGET_CHECK,
                    "metadata claims '$name' is INSTALLED but its live directory is missing (CASE D); " +
                        "run package reconciliation before installing"))
            stateRecord?.installed == true ->
                return failure(PackageInstallResult.TransactionConflict(name, Phase.TARGET_CHECK,
                    "'$name' is already installed; first-install never overwrites an existing package"))
            liveExists ->
                return failure(PackageInstallResult.InconsistentState(name, Phase.TARGET_CHECK,
                    "a live directory exists for '$name' with no INSTALLED state (orphan, CASE E); " +
                        "run package reconciliation before installing"))
        }
        // Reconcile stale staging for this package (target absent + not installed).
        val stagingDir = packageStagingDir(root, name)
        if (stagingDir.exists() && !removeRecursively(stagingDir)) {
            return failure(PackageInstallResult.FilesystemFailed(name, Phase.TARGET_CHECK,
                "stale staging for '$name' could not be cleared before staging"))
        }

        // ---- PHASE C: staging ----
        if (!ensureDirectory(stagingRoot(root)) || !ensureDirectory(stagingDir)) {
            return failure(PackageInstallResult.FilesystemFailed(name, Phase.STAGING,
                "the staging directory for '$name' could not be created"))
        }
        val canonicalBytes = manifest.canonicalBytes
        if (!writeBytes(File(stagingDir, PackageLayout.RESERVED_MANIFEST_FILE), canonicalBytes)) {
            cleanupStaging(stagingDir)
            return failure(PackageInstallResult.FilesystemFailed(name, Phase.STAGING,
                "the canonical manifest could not be written into staging for '$name'"))
        }
        for (entry in manifest.files) {
            val parent = File(stagingDir, entry.path).parentFile
            if (parent != null && !ensureDirectory(parent)) {
                cleanupStaging(stagingDir)
                return failure(PackageInstallResult.FilesystemFailed(name, Phase.STAGING,
                    "a required parent directory could not be created for '${entry.path}'"))
            }
            val source = File(sourceDirectory, entry.path)
            val target = File(stagingDir, entry.path)
            val actual = copyStreamingHashing(source, target)
            if (actual == null) {
                cleanupStaging(stagingDir)
                return failure(PackageInstallResult.FilesystemFailed(name, Phase.STAGING,
                    "could not copy payload file '${entry.path}' into staging"))
            }
            if (actual != entry.sha256) {
                cleanupStaging(stagingDir)
                return failure(PackageInstallResult.IntegrityFailed(name, Phase.STAGING,
                    "payload file '${entry.path}' failed its digest while copying (declared " +
                        "${entry.sha256}, computed $actual)"))
            }
        }

        // ---- PHASE D: full staged verification ----
        val stagedCheck = PackageTreeValidator.validate(stagingDir, manifest, allowVerifiedMarker = true)
        if (!stagedCheck.valid) {
            cleanupStaging(stagingDir)
            return classifyTreeFailure(name, Phase.STAGED_VERIFICATION, stagedCheck)
        }

        // ---- PHASE E: verified marker (only after staged verification succeeds) ----
        val markerFile = File(stagingDir, PackageLayout.VERIFIED_MARKER_FILE)
        if (!writeBytes(markerFile, ByteArray(0))) {
            cleanupStaging(stagingDir)
            return failure(PackageInstallResult.FilesystemFailed(name, Phase.MARKER_WRITE,
                "the verified marker could not be written for '$name'"))
        }

        // ---- Dependency and conflict checks (exact, no resolver) ----
        val prePromotion = dependencyAndConflictCheck(root, manifest)
        if (prePromotion != null) {
            cleanupStaging(stagingDir)
            return prePromotion
        }

        // ---- PHASE F: final target-absent re-check ----
        val stateNow = PackageStateFile.read(stateFile)
        if (stateNow?.installed == true || liveDir.exists()) {
            cleanupStaging(stagingDir)
            return failure(PackageInstallResult.TransactionConflict(name, Phase.FINAL_TARGET_CHECK,
                "'$name' appeared as installed before promotion; nothing was overwritten"))
        }

        // ---- PHASE G: atomic promotion ----
        if (!ensureDirectory(livePackagesRoot(root))) {
            cleanupStaging(stagingDir)
            return failure(PackageInstallResult.FilesystemFailed(name, Phase.PROMOTION,
                "the packages directory could not be created for '$name'"))
        }
        // A rename either succeeds and consumes staging, or fails and leaves both
        // paths unchanged; never a recursive copy, never an overwrite of a live
        // target. The resulting filesystem state is inspected explicitly because
        // File.renameTo() cannot be trusted from a true/false return alone.
        val renameSucceeded = stagingDir.renameTo(liveDir)
        val stagingGone = !stagingDir.exists()
        val liveInPlace = liveDir.isDirectory
        if (stagingGone && liveInPlace && !renameSucceeded) {
            // rename reported false yet the filesystem shows the promotion happened.
            // Android's File.renameTo can report false for a successful syscall in
            // rare cases; the inspected state is authoritative and consistent.
            return promoteAndVerify(name, manifest, liveDir, stateFile, stagingDir)
        }
        if (!renameSucceeded || !stagingGone || !liveInPlace) {
            return failure(PackageInstallResult.FilesystemFailed(name, Phase.PROMOTION,
                "promotion of the verified staging for '$name' did not complete: rename=" +
                    "$renameSucceeded stagingStillPresent=${!stagingGone} liveTarget=${liveInPlace}; " +
                    "the existing live target was never overwritten"))
        }
        return promoteAndVerify(name, manifest, liveDir, stateFile, stagingDir)
    }

    /**
     * Reconciles package transaction state under [root] using only the durable
     * package facts (staging dirs, live dirs, state records) and the recovery
     * identity rule (canonical manifest SHA-256 equality plus full live digest
     * verification). Never scans or modifies the base userspace, never executes
     * package content, and never auto-installs arbitrary staging or source trees.
     */
    fun reconcile(root: File): ReconcileReport {
        val items = mutableListOf<ReconcileItem>()
        val names = sortedSetOf<String>()
        val stagingRootDir = stagingRoot(root)
        val packagesRootDir = livePackagesRoot(root)
        val metadataDir = metadataPackagesRoot(root)
        if (stagingRootDir.isDirectory) {
            stagingRootDir.listFiles()?.forEach { child ->
                if (child.isDirectory && PackageManifest.isValidPackageName(child.name)) names += child.name
            }
        }
        if (packagesRootDir.isDirectory) {
            packagesRootDir.listFiles()?.forEach { child ->
                if (child.isDirectory && PackageManifest.isValidPackageName(child.name)) names += child.name
            }
        }
        if (metadataDir.isDirectory) {
            metadataDir.listFiles()?.forEach { child ->
                val fileName = child.name
                if (fileName.endsWith(PackageLayout.PACKAGE_STATE_FILE_SUFFIX)) {
                    val base = fileName.removeSuffix(PackageLayout.PACKAGE_STATE_FILE_SUFFIX)
                    if (PackageManifest.isValidPackageName(base)) names += base
                }
            }
        }
        for (name in names) {
            reconcileOne(root, name, stagingRootDir, packagesRootDir, metadataDir, items)
        }
        return ReconcileReport(items)
    }

    // ---- Post-promotion verification and state commit ----

    private fun promoteAndVerify(
        name: String,
        manifest: PackageManifest,
        liveDir: File,
        stateFile: File,
        stagingDir: File,
    ): PackageInstallResult {
        val liveCheck = PackageTreeValidator.validate(liveDir, manifest, allowVerifiedMarker = true)
        if (!liveCheck.valid) {
            // The promoted tree failed verification; it is not deleted (repair
            // tooling needs it) and INSTALLED is never claimed.
            return failure(classifyTreeFailure(name, Phase.LIVE_VERIFICATION, liveCheck))
        }
        val manifestSha256 = BaseUserspaceFiles.digestHex(manifest.canonicalBytes)
        if (!PackageStateFile.writeInstalled(stateFile, manifest.version, manifest.arch, manifestSha256)) {
            // The live tree is valid and carries the verified marker, so the next
            // reconciliation completes the interrupted state commit rather than
            // reporting an orphan.
            return failure(PackageInstallResult.FilesystemFailed(name, Phase.STATE_COMMIT,
                "'$name' is installed and verified but the INSTALLED state could not be committed; " +
                    "reconciliation will complete it"))
        }
        // Cleanup: remove the transaction marker from the live tree (never
        // payload). Staging was consumed by the promotion rename. A cleanup
        // failure never invalidates the installed package.
        removeQuiet(File(liveDir, PackageLayout.VERIFIED_MARKER_FILE))
        removeQuiet(stagingDir)
        return PackageInstallResult.Success(
            name = name,
            version = manifest.version,
            arch = manifest.arch,
            manifestSha256 = manifestSha256,
            installedDirectory = liveDir,
            stateFile = stateFile,
        )
    }

    // ---- Dependency / conflict ----

    /**
     * Exact dependency and conflict checks against INSTALLED packages only
     * (INSTALLED state AND a live directory; a state that claims INSTALLED over a
     * missing live directory is an inconsistent dependency and rejects). Version
     * constraints are exact (`foo` or `foo=1.0`); no ranges, no resolver, no
     * automatic installation or removal.
     */
    private fun dependencyAndConflictCheck(
        root: File,
        manifest: PackageManifest,
    ): PackageInstallResult.Failure? {
        val name = manifest.name
        for (dependency in manifest.depends) {
            val record = PackageStateFile.read(PackageStateFile.stateFileFor(root, dependency.name))
            val live = File(livePackagesRoot(root), dependency.name).exists()
            if (record?.installed != true || !live) {
                return PackageInstallResult.DependencyFailed(name, Phase.DEPENDENCY_CHECK,
                    "'$name' depends on '${dependency.canonicalText}' which is not installed")
            }
            if (dependency.version != null && record.version != dependency.version) {
                return PackageInstallResult.DependencyFailed(name, Phase.DEPENDENCY_CHECK,
                    "'$name' depends on '${dependency.canonicalText}' but installed " +
                        "'${dependency.name}' is version '${record.version ?: "unknown"}'")
            }
        }
        for (conflict in manifest.conflicts) {
            val record = PackageStateFile.read(PackageStateFile.stateFileFor(root, conflict.name))
            val live = File(livePackagesRoot(root), conflict.name).exists()
            if (record?.installed == true && live) {
                if (conflict.version == null || record.version == conflict.version) {
                    return PackageInstallResult.ConflictDetected(name, Phase.DEPENDENCY_CHECK,
                        "'$name' conflicts with installed '${conflict.canonicalText}'")
                }
            }
        }
        return null
    }

    // ---- Reconciliation cases (CASE A-E) ----

    private fun reconcileOne(
        root: File,
        name: String,
        stagingRootDir: File,
        packagesRootDir: File,
        metadataDir: File,
        items: MutableList<ReconcileItem>,
    ) {
        val stagingDir = File(stagingRootDir, name)
        val liveDir = File(packagesRootDir, name)
        val stateFile = File(metadataDir, name + PackageLayout.PACKAGE_STATE_FILE_SUFFIX)
        val hasStaging = stagingDir.isDirectory
        val stagingVerified = File(stagingDir, PackageLayout.VERIFIED_MARKER_FILE).isFile
        val hasLive = liveDir.exists()
        val installed = PackageStateFile.read(stateFile)?.installed == true

        when {
            // CASE A: incomplete/failed staging (no verified marker).
            hasStaging && !stagingVerified -> {
                val removed = removeRecursively(stagingDir)
                items += ReconcileItem(name,
                    observed = "incomplete staging (no verified marker)",
                    action = if (removed) "removed the stale staging transaction" else "removal failed",
                    ok = removed)
            }

            // CASE B: verified staging that was never promoted (live absent, state
            // absent): never auto-install; the stale staging is removed.
            hasStaging && stagingVerified && !hasLive && !installed -> {
                val removed = removeRecursively(stagingDir)
                items += ReconcileItem(name,
                    observed = "verified staging with no live target and no state",
                    action = if (removed) "removed the stale verified staging (no auto-install)" else "removal failed",
                    ok = removed)
            }

            // Completed install whose cleanup did not finish (live present,
            // state INSTALLED): tidy the leftover staging and any live marker.
            hasStaging && stagingVerified && hasLive && installed -> {
                removeRecursively(stagingDir)
                removeQuiet(File(liveDir, PackageLayout.VERIFIED_MARKER_FILE))
                items += ReconcileItem(name,
                    observed = "verified staging alongside an installed live package",
                    action = "cleaned leftover staging and transaction marker",
                    ok = true)
            }

            // CASE C: verified staging AND a live target AND no state - an
            // interrupted post-promotion transaction. Complete it only when the
            // live canonical-manifest SHA-256 equals the staged manifest's and the
            // whole live payload verifies; otherwise leave both untouched.
            hasStaging && stagingVerified && hasLive && !installed -> {
                val completion = tryCompleteInterrupted(name, stagingDir, liveDir, stateFile)
                items += ReconcileItem(name,
                    observed = "verified staging with a live target but no state (interrupted promotion)",
                    action = completion.action,
                    ok = completion.ok)
            }

            // CASE D: state claims INSTALLED but the live target is missing.
            installed && !hasLive -> {
                items += ReconcileItem(name,
                    observed = "state claims INSTALLED but the live package is missing",
                    action = "reported inconsistent; nothing fabricated or deleted (repair tooling required)",
                    ok = false)
            }

            // CASE E: live target present, no state, and no matching verified
            // staging - an uncommitted/orphaned package directory. When the live
            // tree itself still carries the verified marker it is an interrupted
            // post-promotion transaction and is completed after full verification;
            // otherwise it is reported, never auto-marked or deleted.
            !installed && hasLive -> {
                val markerInLive = File(liveDir, PackageLayout.VERIFIED_MARKER_FILE).isFile
                if (markerInLive) {
                    val completion = tryCompleteInterrupted(name, null, liveDir, stateFile)
                    items += ReconcileItem(name,
                        observed = "orphan live package carrying the verified marker (interrupted promotion)",
                        action = completion.action,
                        ok = completion.ok)
                } else {
                    items += ReconcileItem(name,
                        observed = "orphaned live package directory with no state and no verified staging",
                        action = "reported inconsistent; not marked INSTALLED and not deleted",
                        ok = false)
                }
            }
        }
    }

    /** A tiny mutable summary so [reconcileOne] can fold success/failure cleanly. */
    private data class InterruptedOutcome(val action: String, val ok: Boolean)

    /**
     * Attempts to complete an interrupted post-promotion transaction for [name].
     * [stagingDir] may be null when the verified evidence lives only in the live
     * tree's own marker. Identity is established by canonical manifest SHA-256:
     * when staging is present its manifest must canonical-match the live manifest
     * (same name, version, canonical bytes); INSTALLED is committed only after the
     * live tree fully re-verifies. Nothing is executed and nothing is auto-installed.
     */
    private fun tryCompleteInterrupted(
        name: String,
        stagingDir: File?,
        liveDir: File,
        stateFile: File,
    ): InterruptedOutcome {
        val liveManifest = readManifestQuiet(File(liveDir, PackageLayout.RESERVED_MANIFEST_FILE))
        if (liveManifest == null) {
            return InterruptedOutcome(
                "live manifest missing or unparseable; reported for repair", false)
        }
        if (stagingDir != null) {
            val stagedManifest = readManifestQuiet(File(stagingDir, PackageLayout.RESERVED_MANIFEST_FILE))
            if (stagedManifest == null || stagedManifest.name != liveManifest.name ||
                stagedManifest.version != liveManifest.version ||
                !stagedManifest.canonicalBytes.contentEquals(liveManifest.canonicalBytes)
            ) {
                return InterruptedOutcome(
                    "staged and live canonical manifests do not match; reported for repair", false)
            }
        }
        val liveCheck = PackageTreeValidator.validate(liveDir, liveManifest, allowVerifiedMarker = true)
        if (!liveCheck.valid) {
            return InterruptedOutcome(
                "live package tree did not fully verify; not marked INSTALLED", false)
        }
        val manifestSha256 = BaseUserspaceFiles.digestHex(liveManifest.canonicalBytes)
        if (!PackageStateFile.writeInstalled(stateFile, liveManifest.version, liveManifest.arch, manifestSha256)) {
            return InterruptedOutcome("live tree verified but INSTALLED state could not be committed", false)
        }
        removeQuiet(File(liveDir, PackageLayout.VERIFIED_MARKER_FILE))
        if (stagingDir != null) removeRecursively(stagingDir)
        return InterruptedOutcome("completed the interrupted transaction (INSTALLED committed)", true)
    }

    // ---- Path composition ----

    private fun stagingRoot(root: File): File =
        File(root, PackageLayout.STAGING_PACKAGES_RELATIVE_DIR)

    private fun livePackagesRoot(root: File): File =
        File(root, PackageLayout.PACKAGES_RELATIVE_DIR)

    private fun metadataPackagesRoot(root: File): File =
        File(root, PackageLayout.PACKAGE_METADATA_RELATIVE_DIR)

    private fun packageLiveDir(root: File, name: String): File =
        File(livePackagesRoot(root), name)

    private fun packageStagingDir(root: File, name: String): File =
        File(stagingRoot(root), name)

    // ---- Helpers ----

    /** Reads and parses a package tree's reserved manifest, or null. */
    private fun readManifestQuiet(manifestFile: File): PackageManifest? {
        val bytes = PackageTreeValidator.readBytesQuiet(manifestFile) ?: return null
        return runCatching { PackageManifest.parse(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    }

    private fun classifyTreeFailure(
        name: String,
        phase: Phase,
        report: PackageTreeValidator.Report,
    ): PackageInstallResult.Failure {
        if (report.structuralErrors.isNotEmpty() || report.unsafePaths.isNotEmpty() ||
            report.typeErrors.isNotEmpty()
        ) {
            return PackageInstallResult.PolicyRejected(name, phase, describeTreeRejection(name, report))
        }
        if (report.missingFiles.isNotEmpty() || report.unexpectedObjects.isNotEmpty() ||
            report.digestMismatches.isNotEmpty()
        ) {
            return PackageInstallResult.IntegrityFailed(name, phase, describeTreeRejection(name, report))
        }
        return PackageInstallResult.Malformed(name, phase, describeTreeRejection(name, report))
    }

    private fun describeTreeRejection(
        name: String,
        report: PackageTreeValidator.Report,
    ): String = buildString {
        append("package tree for '$name' failed verification")
        if (report.missingFiles.isNotEmpty()) {
            append("; missing ").append(report.missingFiles.size).append(" payload file(s)")
        }
        if (report.typeErrors.isNotEmpty()) {
            append("; type error on ").append(report.typeErrors.first())
        }
        if (report.digestMismatches.isNotEmpty()) {
            append("; digest mismatch on ").append(report.digestMismatches.first())
        }
        if (report.unexpectedObjects.isNotEmpty()) {
            append("; unexpected object ").append(report.unexpectedObjects.first())
        }
        if (report.structuralErrors.isNotEmpty()) {
            append("; ").append(report.structuralErrors.first())
        }
        if (report.unsafePaths.isNotEmpty()) {
            append("; unsafe path ").append(report.unsafePaths.first())
        }
        if (!report.manifestPresent || !report.manifestIsRegularFile || !report.manifestParses ||
            !report.manifestIdentityMatches
        ) {
            append("; reserved manifest missing/non-canonical")
        }
    }

    private fun failure(result: PackageInstallResult.Failure): PackageInstallResult = result

    /** Streams [source] into [target] while computing SHA-256; returns the hex or
     * null when the copy/read failed. [target]'s parent must already exist. */
    private fun copyStreamingHashing(source: File, target: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(target).use { out ->
            source.inputStream().use { input ->
                val buffer = ByteArray(READ_CHUNK_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
            out.flush()
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrNull()

    private fun writeBytes(file: File, bytes: ByteArray): Boolean = runCatching {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        true
    }.getOrDefault(false)

    private fun ensureDirectory(dir: File): Boolean {
        if (dir.isDirectory) return true
        return runCatching { dir.mkdirs() }.getOrDefault(false) && dir.isDirectory
    }

    private fun removeRecursively(file: File): Boolean =
        if (!file.exists()) true else runCatching { file.deleteRecursively() }.getOrDefault(false)

    private fun removeQuiet(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    /** Removes this transaction's own staging tree after a deterministic failure;
     * strictly scoped to [stagingDir], never anything outside it. */
    private fun cleanupStaging(stagingDir: File) {
        removeRecursively(stagingDir)
    }
}
