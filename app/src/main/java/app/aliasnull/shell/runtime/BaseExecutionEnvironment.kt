package app.aliasnull.shell.runtime

import app.aliasnull.shell.bootstrap.BaseUserspaceArtifact
import java.io.File

/**
 * The explicit, verified "userspace execution environment" of the bundled
 * AliasNull base executable (Part 27-T1).
 *
 * The model represents exactly the information the runtime has already
 * established and nothing user-supplied:
 *
 *   - [installedRoot] / [installedExecutable]: the installed base-userspace root
 *     the bootstrap verified and the bundled executable under it
 *     ([BaseUserspaceArtifact.EXECUTABLE_FILE]);
 *   - [workingDirectory]: the one real, deterministic controlled working
 *     directory the execution-environment layer created inside the verified base
 *     root (its canonical absolute path is the child's cwd);
 *   - [variables]: the one fixed AliasNull-owned environment override
 *     ([NativeExecutionPolicy.baseExecutionEnvironmentOverrides]);
 *   - [argv]: the [LaunchMode.LINKER_LAUNCH] host argv
 *     ([NativeExecutionPolicy.baseExecutableInvocation]).
 *
 * The model cannot be constructed from arbitrary UI strings: its constructor is
 * private and the only entry point is [BaseExecutionEnvironment.prepare], which
 * a caller supplies with the verified installed root - the runtime passes it only
 * after [app.aliasnull.shell.bootstrap.BaseUserspaceBootstrap.installedCheck]
 * reports ready - and which derives the working directory and the environment
 * from the fixed base root, [BaseUserspaceArtifact] and
 * [NativeExecutionPolicy]. It is never proof by directory existence alone: the
 * installed executable is the one the bootstrap digest- and ELF-verified, and a
 * working directory that cannot be created/validated is reported as
 * [BaseExecutionEnvironmentResult.NotReady], never accepted.
 */
internal class BaseExecutionEnvironment private constructor(
    val installedRoot: File,
    val workingDirectory: File,
    val variables: Map<String, String>,
) {
    /** The bundled base executable under [installedRoot], verified by the bootstrap. */
    val installedExecutable: File
        get() = File(installedRoot, BaseUserspaceArtifact.EXECUTABLE_FILE)

    /** The canonical absolute path of [workingDirectory]; the child's controlled cwd. */
    val workingDirectoryPath: String
        get() = workingDirectory.path

    /** The pinned [LaunchMode.LINKER_LAUNCH] host argv for the verified executable. */
    val argv: List<String>
        get() = NativeExecutionPolicy.baseExecutableInvocation(installedRoot)

    companion object {

        /**
         * The fixed directory name, directly under the verified base-userspace
         * root, of the controlled working directory for the base-environment
         * diagnostic (Part 27-T1). Deterministic and never user-selectable. The
         * bootstrap owns the root's metadata/tmp/userspace layers; this sibling is
         * created by the execution-environment layer and is not part of the
         * installed-artifact tree, so creating it never perturbs installed-tree
         * validation.
         */
        const val WORK_DIR_NAME = "work"

        /**
         * Prepares the one controlled execution environment for the verified base
         * userspace at [installedRoot] (whose root is [baseRoot]). [installedRoot]
         * must be the base-userspace directory the bootstrap verified ready; the
         * caller (the runtime manager) supplies it only after
         * [app.aliasnull.shell.bootstrap.BaseUserspaceBootstrap.installedCheck]
         * reports ready. Creates/validates the deterministic working directory
         * inside [baseRoot] with the narrow owner-only 0700 mode, verifies it is a
         * real directory (never a symlink) strictly inside the base root, and
         * returns a [BaseExecutionEnvironmentResult.Ready] whose working directory
         * and environment derive solely from that verified root and
         * [NativeExecutionPolicy]. Any failure to create or validate the working
         * directory is reported as [BaseExecutionEnvironmentResult.NotReady] with a
         * plain reason - it is never accepted as if it existed. Blocking
         * filesystem work; call from a background thread.
         */
        fun prepare(
            baseRoot: File,
            installedRoot: File,
        ): BaseExecutionEnvironmentResult {
            val dir = workingDirectory(baseRoot)
            return try {
                if (!dir.exists() && !dir.mkdirs()) {
                    return notReady("could not create the controlled working directory: ${dir.path}")
                }
                val mode = android.system.Os.lstat(dir.path).st_mode
                if ((mode and S_IFMT) == S_IFLNK) {
                    return notReady("the controlled working path is a symbolic link, not a directory: ${dir.path}")
                }
                if ((mode and S_IFMT) != S_IFDIR) {
                    return notReady("the controlled working path is not a directory: ${dir.path}")
                }
                // Re-affirm the narrow owner-only mode deterministically (0700).
                android.system.Os.chmod(dir.path, MODE_OWNER_RWX)
                val canonicalDir = dir.canonicalFile
                val canonicalBase = baseRoot.canonicalFile
                if (!isStrictlyInside(canonicalDir.path, canonicalBase.path)) {
                    return notReady(
                        "the controlled working directory ${canonicalDir.path} is not inside " +
                            "the base userspace root ${canonicalBase.path}",
                    )
                }
                BaseExecutionEnvironmentResult.Ready(
                    BaseExecutionEnvironment(
                        installedRoot = installedRoot,
                        workingDirectory = canonicalDir,
                        variables = NativeExecutionPolicy.baseExecutionEnvironmentOverrides,
                    ),
                )
            } catch (error: Throwable) {
                notReady(
                    "could not prepare the controlled working directory: " +
                        (error.message ?: error::class.simpleName ?: "unknown error"),
                )
            }
        }

        /** The deterministic (not-yet-canonicalized) working-directory path for [baseRoot]. */
        internal fun workingDirectory(baseRoot: File): File = File(baseRoot, WORK_DIR_NAME)

        /**
         * True only when the absolute [path] is strictly inside the absolute
         * [root] (a child of root, never root itself and never a sibling or a
         * prefix-collision such as `/a/bb` under `/a/b`). Pure and filesystem-free,
         * so the self-check can assert containment without touching the device.
         */
        internal fun isStrictlyInside(path: String, root: String): Boolean {
            val base = root.trimEnd('/')
            val candidate = path.trimEnd('/')
            if (base.isEmpty() || candidate == base) return false
            return candidate.startsWith("$base/")
        }

        private fun notReady(reason: String): BaseExecutionEnvironmentResult =
            BaseExecutionEnvironmentResult.NotReady(reason)

        // POSIX file-type mask and the narrow owner-only mode (0700) the
        // execution-environment layer applies to the controlled working directory,
        // mirroring the mode convention BaseUserspaceFiles uses for the executable.
        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000
        private const val S_IFLNK = 0xA000
        private const val MODE_OWNER_RWX = 0x1C0
    }
}

/**
 * Outcome of preparing the controlled [BaseExecutionEnvironment].
 *
 * [Ready] carries the environment only when its working directory was created
 * and validated for real; [NotReady] reports why the environment could not be
 * prepared, so a diagnostic that needs it can be surfaced as a readiness fact
 * rather than pretending an environment exists.
 */
internal sealed interface BaseExecutionEnvironmentResult {
    data class Ready(val environment: BaseExecutionEnvironment) : BaseExecutionEnvironmentResult
    data class NotReady(val reason: String) : BaseExecutionEnvironmentResult
}
