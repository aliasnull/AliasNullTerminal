package app.aliasnull.shell.bootstrap

import android.app.Application
import android.util.Log
import java.io.File

/**
 * The result of one [BaseUserspaceBootstrap.run] call.
 *
 * [Ready] means the base userspace for the bundled artifact version is genuinely
 * present and validated; [justInstalled] is true only when this call performed
 * the install (false when an already-valid matching install was reused).
 * [Failed] means the attempt could not produce a valid install; the metadata
 * state is left FAILED (best-effort) and the runtime must not claim readiness.
 * Both carry the attempted artifact [version] and the install [root].
 */
sealed interface BaseUserspaceResult {
    val version: String
    val root: File

    data class Ready(
        override val version: String,
        override val root: File,
        val justInstalled: Boolean,
    ) : BaseUserspaceResult

    data class Failed(
        override val version: String,
        override val root: File,
        val message: String,
    ) : BaseUserspaceResult
}

/**
 * A read-only snapshot of the on-disk base-userspace state, used by the runtime
 * gate and diagnostics. [ready] is the single honest readiness fact: the metadata
 * records INSTALLED AND the installed tree validates for the bundled version. It
 * is never inferred from directory existence alone.
 */
data class BaseUserspaceInstalledCheck(
    val metadataState: BaseUserspaceBootstrapState,
    val metadataVersion: String?,
    val treeValid: Boolean,
    val versionMarker: String?,
    val archMarker: String?,
    val versionMatches: Boolean,
    val archMatches: Boolean,
    val missingFiles: List<String>,
    val mismatchedFiles: List<String>,
    /** Reason the bundled executable is invalid, or null when it is valid. */
    val executableError: String? = null,
) {
    val ready: Boolean
        get() = metadataState == BaseUserspaceBootstrapState.INSTALLED &&
            treeValid && versionMatches && archMatches && executableError == null

    val reason: String
        get() = when {
            metadataState != BaseUserspaceBootstrapState.INSTALLED ->
                "base userspace metadata is ${metadataState.name}"
            missingFiles.isNotEmpty() ->
                "base userspace is missing ${missingFiles.size} required file(s)"
            mismatchedFiles.isNotEmpty() ->
                "base userspace has ${mismatchedFiles.size} file(s) failing integrity"
            executableError != null ->
                "the base userspace executable is invalid: $executableError"
            !versionMatches ->
                "installed base userspace version ($versionMarker) does not match this build"
            !archMatches ->
                "installed base userspace arch ($archMarker) does not match this build"
            else -> "base userspace is not ready"
        }
}

/**
 * Owns the real AliasNull base-userspace bootstrap (Part 27-R): it installs the
 * bundled, versioned base artifact into the app-private runtime root and keeps
 * an explicit, persisted installation record, so the runtime can report a
 * truthful, verified base-userspace readiness instead of trusting directory
 * existence.
 *
 * Location (under the existing AliasNull runtime root, app-private):
 *
 *   <runtimeRoot>/
 *       ├── metadata/  base-userspace-state        (the INSTALLING/INSTALLED/... record)
 *       ├── tmp/       staging-userspace, backup   (transient staging/backup)
 *       └── userspace/ base/  VERSION ARCH ...     (the installed artifact)
 *
 * This reuses the existing runtime root and adds the userspace layer on top; it
 * does NOT create a second runtime root and writes nothing outside app-private
 * storage. The installed base is real, versioned content verified by digest and
 * marker equality - never a fake rootfs and never marked installed by existence
 * alone.
 *
 * Atomicity: extraction happens into a staging directory; only after staging
 * validates is it promoted into place (rename), and INSTALLED is written only
 * after the promoted tree re-validates. A partially extracted userspace can
 * therefore never be considered installed. Every write target is derived from
 * the fixed allowlisted manifest names and re-checked for path safety, so
 * traversal cannot escape the userspace root. On the next run an INSTALLING
 * record is reconciled (completed if the tree already validates, otherwise the
 * attempt is re-run), so a kill mid-install never leaves a stale INSTALLING as
 * if it were valid.
 *
 * This class does NOT execute anything, does NOT start a process, and does not
 * touch the AN Shell core or the native process runner. It is invoked on a
 * background dispatcher by the runtime manager as part of its normal
 * initialization attempt.
 */
class BaseUserspaceBootstrap(
    application: Application,
    private val runtimeRoot: File,
) {

    private val appContext = application.applicationContext

    /** The userspace layer under the runtime root (parent of the installed base). */
    val userspaceRoot: File get() = File(runtimeRoot, SUBDIR_USERSPACE)

    /** The installed base-artifact directory (the future runner locates this root). */
    val installedUserspaceRoot: File get() = File(userspaceRoot, INSTALL_DIR_NAME)

    private val metadataDir: File get() = File(runtimeRoot, SUBDIR_METADATA)
    private val metadataFile: File get() = File(metadataDir, METADATA_FILE)
    private val tmpDir: File get() = File(runtimeRoot, SUBDIR_TMP)
    private val stagingDir: File get() = File(tmpDir, STAGING_DIR_NAME)
    private val backupDir: File get() = File(tmpDir, BACKUP_DIR_NAME)

    /** The persisted record; default NOT_INSTALLED when no file exists yet. */
    fun currentMetadata(): BaseUserspaceMetadata =
        BaseUserspaceMetadata.readFrom(metadataFile)

    /**
     * A read-only, non-mutating snapshot of whether the installed base is ready.
     * This is what the runtime gate consults; it never triggers an install.
     */
    fun installedCheck(): BaseUserspaceInstalledCheck {
        val metadata = currentMetadata()
        val validation = validateInstalledTree(installedUserspaceRoot)
        return BaseUserspaceInstalledCheck(
            metadataState = metadata.state,
            metadataVersion = metadata.artifactVersion,
            treeValid = validation.valid,
            versionMarker = validation.versionMarker,
            archMarker = validation.archMarker,
            versionMatches = validation.versionMatches,
            archMatches = validation.archMatches,
            missingFiles = validation.missingFiles,
            mismatchedFiles = validation.mismatchedFiles,
            executableError = validation.executableError,
        )
    }

    /**
     * Runs one bootstrap attempt: verifies the installed state, installs the
     * bundled artifact when needed (or reconciles an interrupted attempt), and
     * returns the honest result. Idempotent: an already-valid matching install
     * is reused without re-extraction. Blocking filesystem work; call from a
     * background thread.
     */
    fun run(): BaseUserspaceResult {
        val dirError = ensureBaseDirectories()
        if (dirError != null) {
            return failure("base userspace directory could not be prepared: $dirError")
        }
        val check = installedCheck()
        if (check.ready) {
            Log.i(TAG, "Base userspace already installed and valid (version ${check.metadataVersion})")
            return ready(justInstalled = false)
        }
        // An interrupted attempt whose tree already validates is completed, not
        // re-extracted: it never crashed with a half-written base directory.
        if (check.metadataState == BaseUserspaceBootstrapState.INSTALLING && check.treeValid) {
            if (writeMetadata(BaseUserspaceBootstrapState.INSTALLED, BaseUserspaceArtifact.VERSION)) {
                Log.i(TAG, "Completed an interrupted base userspace install (version ${BaseUserspaceArtifact.VERSION})")
                return ready(justInstalled = false)
            }
        }
        return install()
    }

    // ---- Install path ----

    private fun install(): BaseUserspaceResult {
        if (!writeMetadata(BaseUserspaceBootstrapState.INSTALLING, BaseUserspaceArtifact.VERSION)) {
            return failure("could not begin the base userspace install (metadata not writable)")
        }
        return try {
            // Clean staging from any previous attempt, then extract the bundled
            // artifact into it, digest-validating every file before it is written.
            wipe(stagingDir)
            if (!stagingDir.mkdirs() || !stagingDir.isDirectory || !stagingDir.canWrite()) {
                return failWithMetadata("could not create the staging directory")
            }
            for ((relative, expected) in BaseUserspaceArtifact.FILES) {
                if (!BaseUserspaceFiles.isSafeRelativePath(relative)) {
                    return failWithMetadata("bundled artifact contains an unsafe path: '$relative'")
                }
                val bytes = readBundledFile(relative)
                    ?: return failWithMetadata("bundled artifact file is missing from the APK: '$relative'")
                if (BaseUserspaceFiles.digestHex(bytes) != expected) {
                    return failWithMetadata("bundled artifact file failed its digest: '$relative'")
                }
                val target = File(stagingDir, relative)
                target.writeBytes(bytes)
                if (BaseUserspaceArtifact.isExecutableFile(relative) &&
                    !BaseUserspaceFiles.applyExecutableOwnerMode(target)
                ) {
                    return failWithMetadata("could not set the executable permission on '$relative'")
                }
            }
            val staged = validateInstalledTree(stagingDir)
            if (!staged.valid) {
                return failWithMetadata("staged base userspace failed validation")
            }

            // Atomic promotion: move any previous base aside, move the validated
            // staging into place, then delete the backup. A failure rolls the
            // previous base back into place.
            wipe(backupDir)
            if (installedUserspaceRoot.exists() &&
                !installedUserspaceRoot.renameTo(backupDir)
            ) {
                return failWithMetadata("could not move the previous base userspace aside")
            }
            if (!stagingDir.renameTo(installedUserspaceRoot)) {
                if (backupDir.exists()) {
                    runCatching { backupDir.renameTo(installedUserspaceRoot) }
                }
                return failWithMetadata("could not promote the staged base userspace into place")
            }
            wipe(backupDir)

            val final = validateInstalledTree(installedUserspaceRoot)
            if (!final.valid) {
                return failWithMetadata("installed base userspace failed final validation")
            }
            if (!writeMetadata(BaseUserspaceBootstrapState.INSTALLED, BaseUserspaceArtifact.VERSION)) {
                // The tree is valid; next launch reconciles INSTALLING -> INSTALLED.
                return failure("installed base userspace but could not persist the INSTALLED record")
            }
            Log.i(TAG, "Base userspace installed and validated (version ${BaseUserspaceArtifact.VERSION})")
            ready(justInstalled = true)
        } catch (error: Throwable) {
            Log.e(TAG, "Base userspace install threw: ${error.message ?: error::class.simpleName}")
            failWithMetadata("base userspace install failed: ${error.message ?: "unexpected error"}")
        }
    }

    // ---- Helpers ----

    /**
     * Validates [root] as an installed/staged base tree against the authoritative
     * manifest, including the bundled executable's permission and 64-bit AArch64
     * ELF format. Every install and reuse decision consults this same check, so
     * readiness can never depend on the executable merely existing.
     */
    private fun validateInstalledTree(root: File): BaseUserspaceTreeValidation =
        BaseUserspaceFiles.validateInstalledTree(
            root = root,
            manifest = BaseUserspaceArtifact.FILES,
            expectedVersion = BaseUserspaceArtifact.VERSION,
            expectedArch = BaseUserspaceArtifact.ARCH,
            executableRelative = BaseUserspaceArtifact.EXECUTABLE_FILE,
        )

    private fun ready(justInstalled: Boolean): BaseUserspaceResult =
        BaseUserspaceResult.Ready(BaseUserspaceArtifact.VERSION, installedUserspaceRoot, justInstalled)

    private fun failure(message: String): BaseUserspaceResult =
        BaseUserspaceResult.Failed(BaseUserspaceArtifact.VERSION, installedUserspaceRoot, message)

    /** Records a FAILED metadata state (best-effort) and returns the failure. */
    private fun failWithMetadata(message: String): BaseUserspaceResult {
        writeMetadata(BaseUserspaceBootstrapState.FAILED, BaseUserspaceArtifact.VERSION)
        Log.e(TAG, message)
        return failure(message)
    }

    private fun writeMetadata(
        state: BaseUserspaceBootstrapState,
        version: String?,
    ): Boolean = runCatching {
        if (!metadataDir.exists() && !metadataDir.mkdirs()) return@runCatching false
        if (!metadataDir.isDirectory || !metadataDir.canWrite()) return@runCatching false
        metadataFile.writeText(
            BaseUserspaceMetadata.encode(BaseUserspaceMetadata(state, version)),
        )
    }.isSuccess

    /** Reads one bundled artifact file's bytes, or null when absent/unreadable. */
    private fun readBundledFile(relative: String): ByteArray? = runCatching {
        appContext.assets.open(BaseUserspaceArtifact.assetPath(relative)).use { stream ->
            stream.readBytes()
        }
    }.getOrNull()

    private fun ensureBaseDirectories(): String? {
        for (dir in listOf(runtimeRoot, userspaceRoot, metadataDir, tmpDir)) {
            if (!dir.exists() && !dir.mkdirs()) return "could not create ${dir.path}"
            if (!dir.isDirectory || !dir.canWrite()) return "not writable: ${dir.path}"
        }
        return null
    }

    /** Recursively deletes [file] when it exists. */
    private fun wipe(file: File) {
        if (file.exists()) runCatching { file.deleteRecursively() }
    }

    private companion object {
        const val TAG = "BaseUserspaceBootstrap"
        const val SUBDIR_USERSPACE = "userspace"
        const val SUBDIR_METADATA = "metadata"
        const val SUBDIR_TMP = "tmp"
        const val INSTALL_DIR_NAME = "base"
        const val METADATA_FILE = "base-userspace-state"
        const val STAGING_DIR_NAME = "staging-userspace"
        const val BACKUP_DIR_NAME = "backup-userspace"
    }
}
