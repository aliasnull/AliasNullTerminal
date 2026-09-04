package app.aliasnull.shell.bootstrap

import android.app.Application
import java.io.File

/** One executed case in a [BaseUserspaceBootstrapSelfCheckReport]. */
internal data class BaseUserspaceSelfCheckCase(
    val label: String,
    val passed: Boolean,
    val detail: String,
)

/** Aggregated result of one [BaseUserspaceBootstrapSelfCheck].run. */
internal data class BaseUserspaceSelfCheckReport(
    val cases: List<BaseUserspaceSelfCheckCase>,
) {
    val allPassed: Boolean
        get() = cases.all { it.passed }

    val passedCount: Int
        get() = cases.count { it.passed }
}

/**
 * Internal, deterministic self-check for the base-userspace bootstrap
 * (Part 27-R, spec §V). It exercises the real bootstrap engine end-to-end
 * against the REAL bundled artifact but inside an isolated scratch root, plus
 * the pure integrity/path functions against crafted states, so every check runs
 * real filesystem operations. Nothing here is mocked and no directory is marked
 * installed by existence alone.
 *
 * This object is deliberately NOT wired to any startup, Shell command or UI
 * path: it is a dormant verification path a future test or diagnostics surface
 * invokes on demand. It performs blocking filesystem work and MUST be called
 * from a background thread, never the Android main thread.
 */
internal object BaseUserspaceBootstrapSelfCheck {

    fun run(application: Application, scratchRoot: File): BaseUserspaceSelfCheckReport {
        wipe(scratchRoot)
        if (!scratchRoot.mkdirs()) {
            return report(
                case("setup", passed = false, detail = "could not create scratch root ${scratchRoot.path}"),
            )
        }
        return report(
            // 1. Artifact discovery + 9. path-safety rules for every bundled name.
            discoveryAndPathSafety(),
            // 2+3. Real install of the bundled artifact into an isolated root.
            freshInstall(application, scratchRoot),
            // 6. Already-installed matching version is reused, not re-extracted.
            secondLaunch(application, scratchRoot),
            // 4. Required-file validation after install (VERSION/ARCH present).
            installedValidation(application, scratchRoot),
            // 5. Metadata creation: INSTALLED with the recorded version.
            metadataRecorded(application, scratchRoot),
            // 7. Corruption detection: an altered installed file invalidates readiness.
            corruptionDetected(application, scratchRoot),
            // 7+8. Partial/interrupted install is never valid and is repaired.
            interruptedInstallRepaired(application, scratchRoot),
            // 8. Failed metadata state is surfaced and repaired by retry (run).
            failedStateRepaired(application, scratchRoot),
            // 10. Every install write stays inside the intended userspace root.
            containment(application, scratchRoot),
            // Part 27-S2 / Part 27-S2-PERM-FIX (Phase 6): the bundled executable
            // must be a real regular file whose genuine lstat mode has the
            // narrow owner-only 0700 owner-execute bit (the narrowest safe mode)
            // and a verified 64-bit AArch64 ELF. A missing, corrupted,
            // non-executable, wrong-type or FAILED installation is detected and
            // repaired without weakening SHA or path containment. Mode checks are
            // deterministic and real (android.system.Os.lstat on the actual file);
            // they prove the mode, never that execve() is permitted under the
            // device's SELinux policy - that is provable only by running the Base
            // Executable diagnostic on a device.
            executableFreshInstallValid(application, scratchRoot),
            correctExecutablePermissionAccepted(application, scratchRoot),
            missingExecutableRepaired(application, scratchRoot),
            corruptedExecutableRepaired(application, scratchRoot),
            removingExecutablePermissionInvalidates(application, scratchRoot),
            nonExecutableRepaired(application, scratchRoot),
            permissionRepairKeepsContainment(application, scratchRoot),
        )
    }

    // ---- Individual checks ----

    private fun discoveryAndPathSafety(): BaseUserspaceSelfCheckCase {
        val bundled = BaseUserspaceArtifact.FILES
        val allSafe = bundled.keys.all { BaseUserspaceFiles.isSafeRelativePath(it) }
        val malicious = listOf("../escape", "../../../escape", "/etc/passwd", "a/../b", "a\\b", "", ".", "..", "a//b")
        val allRejected = malicious.all { !BaseUserspaceFiles.isSafeRelativePath(it) }
        val passed = bundled.isNotEmpty() && allSafe && allRejected
        val detail = if (passed) {
            "${bundled.size} bundled file(s) discovered, all safe; ${malicious.size} traversal-style path(s) rejected"
        } else {
            "bundled=${bundled.size} allSafe=$allSafe traversalRejected=$allRejected"
        }
        return case("artifact discovery and path-safety rules", passed, detail)
    }

    private fun freshInstall(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val bootstrap = freshBootstrap(application, scratchRoot, "fresh")
        val result = bootstrap.run()
        val passed = result is BaseUserspaceResult.Ready &&
            result.justInstalled &&
            bootstrap.installedCheck().ready
        val detail = if (passed) {
            "installed ${BaseUserspaceArtifact.FILES.size} file(s) into ${bootstrap.installedUserspaceRoot.path}"
        } else {
            "run returned: ${describe(result)}; check.ready=${bootstrap.installedCheck().ready}"
        }
        return case("fresh install of the bundled artifact", passed, detail)
    }

    private fun secondLaunch(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "second")
        val bootstrap = freshBootstrap(application, root, "second")
        val first = bootstrap.run()
        val second = bootstrap.run()
        val passed = first is BaseUserspaceResult.Ready && first.justInstalled &&
            second is BaseUserspaceResult.Ready && !second.justInstalled
        return case(
            "already-installed matching version is reused without re-extraction",
            passed,
            "first.justInstalled=${(first as? BaseUserspaceResult.Ready)?.justInstalled} " +
                "second.justInstalled=${(second as? BaseUserspaceResult.Ready)?.justInstalled}",
        )
    }

    private fun installedValidation(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "validated")
        val bootstrap = freshBootstrap(application, root, "validated")
        bootstrap.run()
        val check = bootstrap.installedCheck()
        val present = BaseUserspaceArtifact.REQUIRED_FILE_PATHS.all { path ->
            File(bootstrap.installedUserspaceRoot, path).isFile
        }
        val passed = check.ready && present && check.versionMatches && check.archMatches
        val detail = if (passed) {
            "all required files present; version=${check.versionMarker} arch=${check.archMarker}"
        } else {
            "ready=${check.ready} allPresent=$present versionOk=${check.versionMatches} archOk=${check.archMatches}"
        }
        return case("required-file and marker validation of the installed tree", passed, detail)
    }

    private fun metadataRecorded(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "metadata")
        val bootstrap = freshBootstrap(application, root, "metadata")
        bootstrap.run()
        val metadata = bootstrap.currentMetadata()
        val passed = metadata.state == BaseUserspaceBootstrapState.INSTALLED &&
            metadata.artifactVersion == BaseUserspaceArtifact.VERSION
        return case(
            "metadata records a successful INSTALLED install with the version",
            passed,
            "state=${metadata.state} version=${metadata.artifactVersion}",
        )
    }

    private fun corruptionDetected(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "corrupt")
        val bootstrap = freshBootstrap(application, root, "corrupt")
        bootstrap.run()
        // Alter one installed file's bytes after a valid install.
        val versionFile = File(bootstrap.installedUserspaceRoot, BaseUserspaceArtifact.VERSION_FILE)
        versionFile.appendText("tampered")
        val check = bootstrap.installedCheck()
        val passed = !check.ready && check.mismatchedFiles.contains(BaseUserspaceArtifact.VERSION_FILE)
        return case(
            "corrupted installed content is detected and never trusted",
            passed,
            "ready=${check.ready} mismatched=${check.mismatchedFiles}",
        )
    }

    private fun interruptedInstallRepaired(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "interrupted")
        // Simulate a crash mid-install: INSTALLING metadata with an incomplete tree.
        val bootstrap = freshBootstrap(application, root, "interrupted")
        writeMetadata(bootstrap, BaseUserspaceBootstrapState.INSTALLING)
        val partial = bootstrap.installedUserspaceRoot
        if (!partial.exists() && !partial.mkdirs()) {
            return case("partial install detection and repair", false, "could not prepare partial tree")
        }
        File(partial, BaseUserspaceArtifact.VERSION_FILE).writeText("${BaseUserspaceArtifact.VERSION}\n")
        val before = bootstrap.installedCheck()
        val run = bootstrap.run()
        val passed = !before.ready && run is BaseUserspaceResult.Ready && bootstrap.installedCheck().ready
        return case(
            "partial/interrupted install is never valid and is repaired on retry",
            passed,
            "before.ready=${before.ready} after.state=${bootstrap.currentMetadata().state} " +
                "after.ready=${bootstrap.installedCheck().ready}",
        )
    }

    private fun failedStateRepaired(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "failed")
        val bootstrap = freshBootstrap(application, root, "failed")
        writeMetadata(bootstrap, BaseUserspaceBootstrapState.FAILED)
        val run = bootstrap.run()
        val passed = run is BaseUserspaceResult.Ready && bootstrap.installedCheck().ready
        return case(
            "a FAILED record is surfaced and repaired by the bootstrap retry",
            passed,
            "state=${bootstrap.currentMetadata().state} ready=${bootstrap.installedCheck().ready}",
        )
    }

    private fun containment(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "containment")
        val bootstrap = freshBootstrap(application, root, "containment")
        bootstrap.run()
        val installed = bootstrap.installedUserspaceRoot
        // Every installed file must be exactly a safe manifest path under the root.
        val present = BaseUserspaceArtifact.FILES.keys.all { File(installed, it).isFile }
        val noStrayTopLevel = installed.listFiles()?.size == BaseUserspaceArtifact.FILES.size
        val escaped = BaseUserspaceArtifact.FILES.keys.any { !BaseUserspaceFiles.isSafeRelativePath(it) }
        val passed = present && !escaped && (noStrayTopLevel ?: false)
        return case(
            "every install write stays inside the userspace root",
            passed,
            "files=${installed.listFiles()?.size} under ${installed.path}",
        )
    }

    // ---- Part 27-S2 / Part 27-S2-PERM-FIX executable permission checks ----
    //
    // These checks read the REAL on-disk lstat mode (via android.system.Os) of the
    // executable a fresh install or a repair produced, never File.canExecute().
    // They are deterministic and honest about scope: a passing mode check proves
    // the file is a regular file with the narrow owner-only 0700 owner-execute
    // mode, but it does NOT prove that execve() is permitted on a real device -
    // SELinux may still deny execution, and only the on-device Base Executable
    // diagnostic can establish that.

    private fun executableFreshInstallValid(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "exec-installed")
        val bootstrap = freshBootstrap(application, root, "exec-installed")
        val run = bootstrap.run()
        val relative = BaseUserspaceArtifact.EXECUTABLE_FILE
        val exe = File(bootstrap.installedUserspaceRoot, relative)
        val mode = BaseUserspaceFiles.modeBits(exe)
        val error = BaseUserspaceFiles.executableValidationError(bootstrap.installedUserspaceRoot, relative)
        val shaOk = BaseUserspaceFiles.sha256Of(exe) == BaseUserspaceArtifact.FILES[relative]
        val check = bootstrap.installedCheck()
        val regular = mode != null && BaseUserspaceFiles.isRegularFileMode(mode)
        val ownerExec = mode != null && BaseUserspaceFiles.isOwnerExecutableMode(mode)
        val passed = run is BaseUserspaceResult.Ready && check.ready && exe.isFile &&
            regular && ownerExec && error == null && shaOk
        return case(
            "fresh install creates a real owner-executable 64-bit AArch64 executable",
            passed,
            "ready=${check.ready} regular=$regular ownerExec=$ownerExec " +
                "mode=${mode?.let { Integer.toOctalString(it and PERM_BITS) }} " +
                "executableError=$error shaOk=$shaOk",
        )
    }

    private fun correctExecutablePermissionAccepted(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "exec-mode-accepted")
        val bootstrap = freshBootstrap(application, root, "exec-mode-accepted")
        bootstrap.run()
        val relative = BaseUserspaceArtifact.EXECUTABLE_FILE
        val exe = File(bootstrap.installedUserspaceRoot, relative)
        val mode = BaseUserspaceFiles.modeBits(exe)
        val narrowOwnerOnly = mode != null && BaseUserspaceFiles.isNarrowOwnerOnlyExecutableMode(mode)
        val error = BaseUserspaceFiles.executableValidationError(bootstrap.installedUserspaceRoot, relative)
        val check = bootstrap.installedCheck()
        val passed = check.ready && narrowOwnerOnly && error == null
        return case(
            "the narrow owner-only 0700 executable mode is accepted as valid",
            passed,
            "ready=${check.ready} narrowOwnerOnly=$narrowOwnerOnly " +
                "mode=${mode?.let { Integer.toOctalString(it and PERM_BITS) }} executableError=$error",
        )
    }

    private fun missingExecutableRepaired(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "exec-missing")
        val bootstrap = freshBootstrap(application, root, "exec-missing")
        bootstrap.run()
        val exe = File(bootstrap.installedUserspaceRoot, BaseUserspaceArtifact.EXECUTABLE_FILE)
        exe.delete()
        val before = bootstrap.installedCheck()
        val run = bootstrap.run()
        val passed = !before.ready && run is BaseUserspaceResult.Ready && bootstrap.installedCheck().ready
        return case(
            "a missing executable invalidates readiness and is reinstalled",
            passed,
            "before.ready=${before.ready} missing=${before.missingFiles} " +
                "after.ready=${bootstrap.installedCheck().ready}",
        )
    }

    private fun corruptedExecutableRepaired(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "exec-corrupt")
        val bootstrap = freshBootstrap(application, root, "exec-corrupt")
        bootstrap.run()
        val exe = File(bootstrap.installedUserspaceRoot, BaseUserspaceArtifact.EXECUTABLE_FILE)
        val size = exe.length().toInt()
        if (size > 0) exe.writeBytes(ByteArray(size) { (it % 251).toByte() })
        val before = bootstrap.installedCheck()
        val run = bootstrap.run()
        val passed = !before.ready && run is BaseUserspaceResult.Ready && bootstrap.installedCheck().ready
        return case(
            "corrupted executable content is detected and reinstalled (SHA integrity enforced)",
            passed,
            "before.ready=${before.ready} mismatched=${before.mismatchedFiles} " +
                "after.ready=${bootstrap.installedCheck().ready}",
        )
    }

    private fun removingExecutablePermissionInvalidates(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "exec-non-exec")
        val bootstrap = freshBootstrap(application, root, "exec-non-exec")
        bootstrap.run()
        val exe = File(bootstrap.installedUserspaceRoot, BaseUserspaceArtifact.EXECUTABLE_FILE)
        stripOwnerExec(exe)
        val before = bootstrap.installedCheck()
        val passed = !before.ready &&
            before.executableError != null &&
            before.executableError.contains(OWNER_EXEC_ERROR_TOKEN)
        return case(
            "removing the executable permission makes the installation invalid",
            passed,
            "before.ready=${before.ready} executableError=${before.executableError}",
        )
    }

    private fun nonExecutableRepaired(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "exec-repair")
        val bootstrap = freshBootstrap(application, root, "exec-repair")
        bootstrap.run()
        val exe = File(bootstrap.installedUserspaceRoot, BaseUserspaceArtifact.EXECUTABLE_FILE)
        stripOwnerExec(exe)
        val before = bootstrap.installedCheck()
        val run = bootstrap.run()
        val mode = BaseUserspaceFiles.modeBits(exe)
        val ownerExecRestored = mode != null && BaseUserspaceFiles.isNarrowOwnerOnlyExecutableMode(mode)
        val after = bootstrap.installedCheck()
        val passed = !before.ready && run is BaseUserspaceResult.Ready && after.ready && ownerExecRestored
        return case(
            "the bootstrap repairs a non-executable executable",
            passed,
            "before.ready=${before.ready} executableError=${before.executableError} " +
                "after.ready=${after.ready} ownerExecRestored=$ownerExecRestored " +
                "mode=${mode?.let { Integer.toOctalString(it and PERM_BITS) }}",
        )
    }

    private fun permissionRepairKeepsContainment(application: Application, scratchRoot: File): BaseUserspaceSelfCheckCase {
        val root = scratch(scratchRoot, "exec-repair-containment")
        val bootstrap = freshBootstrap(application, root, "exec-repair-containment")
        bootstrap.run()
        val exe = File(bootstrap.installedUserspaceRoot, BaseUserspaceArtifact.EXECUTABLE_FILE)
        stripOwnerExec(exe)
        bootstrap.run()
        val installed = bootstrap.installedUserspaceRoot
        val present = BaseUserspaceArtifact.FILES.keys.all { File(installed, it).isFile }
        val noStrayTopLevel = installed.listFiles()?.size == BaseUserspaceArtifact.FILES.size
        val escaped = BaseUserspaceArtifact.FILES.keys.any { !BaseUserspaceFiles.isSafeRelativePath(it) }
        val check = bootstrap.installedCheck()
        val passed = check.ready && present && !escaped && (noStrayTopLevel ?: false)
        return case(
            "permission repair does not weaken path containment",
            passed,
            "ready=${check.ready} allPresent=$present noStrayTopLevel=$noStrayTopLevel",
        )
    }

    /** Removes the owner-execute bit from [file] with the real chmod (mode 0600). */
    private fun stripOwnerExec(file: File) {
        android.system.Os.chmod(file.path, OWNER_RW_NO_EXEC)
    }

    // ---- Shared helpers ----

    private fun freshBootstrap(application: Application, scratchRoot: File, name: String): BaseUserspaceBootstrap {
        val root = File(scratchRoot, "$name-root")
        wipe(root)
        root.mkdirs()
        return BaseUserspaceBootstrap(application, root)
    }

    private fun writeMetadata(bootstrap: BaseUserspaceBootstrap, state: BaseUserspaceBootstrapState) {
        val root = bootstrap.installedUserspaceRoot.parentFile.parentFile // the bootstrap's root
        val metadataDir = File(root, "metadata")
        if (!metadataDir.exists()) metadataDir.mkdirs()
        File(metadataDir, "base-userspace-state").writeText(
            BaseUserspaceMetadata.encode(
                BaseUserspaceMetadata(state, BaseUserspaceArtifact.VERSION),
            ),
        )
    }

    private fun scratch(scratchRoot: File, name: String): File {
        val dir = File(scratchRoot, name)
        wipe(dir)
        dir.mkdirs()
        return dir
    }

    private fun wipe(file: File) {
        if (file.exists()) runCatching { file.deleteRecursively() }
    }

    private fun describe(result: BaseUserspaceResult): String = when (result) {
        is BaseUserspaceResult.Ready -> "Ready(justInstalled=${result.justInstalled})"
        is BaseUserspaceResult.Failed -> "Failed(${result.message})"
    }

    private fun case(label: String, passed: Boolean, detail: String) =
        BaseUserspaceSelfCheckCase(label, passed, detail)

    private fun report(vararg cases: BaseUserspaceSelfCheckCase): BaseUserspaceSelfCheckReport =
        BaseUserspaceSelfCheckReport(cases.toList())

    private const val PERM_BITS = 0xFFF // low 12 st_mode bits, for a concise octal display
    private const val OWNER_EXEC_ERROR_TOKEN = "owner-execute"
    private const val OWNER_RW_NO_EXEC = 0x180 // 0600: owner read/write, no execute
}
