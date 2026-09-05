package app.aliasnull.shell.runtime

import app.aliasnull.shell.bootstrap.BaseUserspaceArtifact
import java.io.File

/**
 * The explicit, verified execution environment of the bundled AliasNull
 * base-userspace digest component (Part 27-T2).
 *
 * The model represents exactly the information the runtime has already
 * established and nothing user-supplied:
 *
 *   - [installedRoot] / [installedDigestExecutable]: the installed base-userspace
 *     root the bootstrap verified and the bundled digest component under it
 *     ([BaseUserspaceArtifact.DIGEST_EXECUTABLE_FILE]);
 *   - [workingDirectory]: the one real, deterministic controlled working
 *     directory the environment layer created inside the verified base root (its
 *     canonical absolute path is the child's cwd; the digest shares the same
 *     controlled working directory as the base-execution-environment case);
 *   - [variables]: the one fixed AliasNull-owned environment override
 *     ([NativeExecutionPolicy.baseDigestEnvironmentOverrides]), which names the
 *     verified installed root as the digest's controlled root;
 *   - [argv]: the [app.aliasnull.shell.runtime.native.LaunchMode.LINKER_LAUNCH]
 *     host argv ([NativeExecutionPolicy.baseDigestInvocation]).
 *
 * The model cannot be constructed from arbitrary UI strings: its constructor is
 * private and the only entry point is [BaseDigestEnvironment.prepare], which a
 * caller supplies with the verified installed root - the runtime passes it only
 * after [app.aliasnull.shell.bootstrap.BaseUserspaceBootstrap.installedCheck]
 * reports ready - and which derives the working directory and the environment
 * from the fixed base root, [BaseUserspaceArtifact] and
 * [NativeExecutionPolicy]. The working directory itself is prepared by the
 * exact same validated routine the base-execution-environment case uses
 * ([BaseExecutionEnvironment.prepare]), so the two controlled diagnostics share
 * one controlled working directory and no separate directory logic can drift.
 * It is never proof by directory existence alone: the installed digest component
 * is the one the bootstrap digest-, format- and permission-verified, and a
 * working directory that cannot be created/validated is reported as
 * [BaseDigestEnvironmentResult.NotReady], never accepted.
 */
internal class BaseDigestEnvironment private constructor(
    val installedRoot: File,
    val workingDirectory: File,
    val variables: Map<String, String>,
) {
    /** The bundled digest component under [installedRoot], verified by the bootstrap. */
    val installedDigestExecutable: File
        get() = File(installedRoot, BaseUserspaceArtifact.DIGEST_EXECUTABLE_FILE)

    /** The canonical absolute path of [workingDirectory]; the child's controlled cwd. */
    val workingDirectoryPath: String
        get() = workingDirectory.path

    /** The pinned [LaunchMode.LINKER_LAUNCH] host argv for the verified component. */
    val argv: List<String>
        get() = NativeExecutionPolicy.baseDigestInvocation(installedRoot)

    companion object {

        /**
         * Prepares the one controlled execution environment for the verified base
         * digest component at [installedRoot] (whose root is [baseRoot]).
         * [installedRoot] must be the base-userspace directory the bootstrap
         * verified ready; the caller (the runtime manager) supplies it only after
         * [app.aliasnull.shell.bootstrap.BaseUserspaceBootstrap.installedCheck]
         * reports ready. The controlled working directory is created/validated by
         * [BaseExecutionEnvironment.prepare] (the same routine and the same
         * `work` directory the base-execution-environment diagnostic uses), and a
         * [BaseDigestEnvironmentResult.Ready] carries the digest environment whose
         * variables and argv derive solely from that verified root and
         * [NativeExecutionPolicy]. Any failure to create or validate the working
         * directory is reported as [BaseDigestEnvironmentResult.NotReady] with a
         * plain reason - it is never accepted as if it existed. Blocking
         * filesystem work; call from a background thread.
         */
        fun prepare(
            baseRoot: File,
            installedRoot: File,
        ): BaseDigestEnvironmentResult {
            val prepared = BaseExecutionEnvironment.prepare(baseRoot, installedRoot)
            return when (prepared) {
                is BaseExecutionEnvironmentResult.NotReady ->
                    BaseDigestEnvironmentResult.NotReady(prepared.reason)
                is BaseExecutionEnvironmentResult.Ready ->
                    BaseDigestEnvironmentResult.Ready(
                        BaseDigestEnvironment(
                            installedRoot = installedRoot,
                            workingDirectory = prepared.environment.workingDirectory,
                            variables = NativeExecutionPolicy.baseDigestEnvironmentOverrides(installedRoot),
                        ),
                    )
            }
        }
    }
}

/**
 * Outcome of preparing the controlled [BaseDigestEnvironment].
 *
 * [Ready] carries the environment only when its working directory was created
 * and validated for real; [NotReady] reports why the environment could not be
 * prepared, so a diagnostic that needs it can be surfaced as a readiness fact
 * rather than pretending an environment exists.
 */
internal sealed interface BaseDigestEnvironmentResult {
    data class Ready(val environment: BaseDigestEnvironment) : BaseDigestEnvironmentResult
    data class NotReady(val reason: String) : BaseDigestEnvironmentResult
}
