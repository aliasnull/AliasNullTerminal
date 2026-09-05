package app.aliasnull.shell.runtime

import app.aliasnull.shell.bootstrap.BaseUserspaceArtifact
import java.io.File

/** One derivation/containment assertion in a [BaseExecutionEnvironmentSelfCheckReport]. */
internal data class BaseExecutionEnvironmentSelfCheckCase(
    val label: String,
    val expectedMet: Boolean,
    val detail: String,
)

/** Aggregated result of one [BaseExecutionEnvironmentSelfCheck] run. */
internal data class BaseExecutionEnvironmentSelfCheckReport(
    val cases: List<BaseExecutionEnvironmentSelfCheckCase>,
) {
    val allPassed: Boolean
        get() = cases.all { it.expectedMet }

    val passedCount: Int
        get() = cases.count { it.expectedMet }
}

/**
 * Dormant, process-free and filesystem-free self-check for the pure derivation
 * and containment rules of the controlled base execution environment (Part
 * 27-T1). Every case calls the REAL derivation functions the model uses -
 * [BaseExecutionEnvironment.workingDirectory], the fixed
 * [BaseExecutionEnvironment.WORK_DIR_NAME] and the manifest's allowlisted
 * executable file name, and the strict-containment predicate
 * [BaseExecutionEnvironment.isStrictlyInside] - on deterministic, device-free
 * paths and asserts the true result; nothing is fabricated and no directory is
 * created or touched. The assertions fix the environment model's shape:
 *
 *   - the controlled working directory is always the single fixed name directly
 *     under the verified base root (never user-selectable, never a UI string);
 *   - the installed base executable and the controlled working directory are
 *     both strictly inside that verified base root;
 *   - containment never accepts the base root itself, a sibling, an unrelated
 *     path or a prefix collision such as `/a/bb` under `/a/b`.
 *
 * Device-dependent readiness - the working directory is a real (never symlink)
 * 0700 directory, its canonical path is strictly inside the base root, and any
 * failure to create/validate it is reported - is NOT asserted here: it is
 * enforced for real by [BaseExecutionEnvironment.prepare], which returns
 * [BaseExecutionEnvironmentResult.NotReady] rather than ever accepting an
 * un-prepared directory, and the runtime reports that as a readiness fact before
 * any process is attempted. Bootstrap integrity remains authoritative in
 * [app.aliasnull.shell.bootstrap.BaseUserspaceBootstrap] and the CI SHA-256 gate.
 *
 * Like [NativeExecutionPolicySelfCheck], this object is deliberately NOT wired to
 * any Shell command, UI or startup path; it exists so the codebase (or a future
 * test surface) can verify the derivation contract on demand by calling [run].
 * Every assertion is deterministic and depends on no device state.
 */
internal object BaseExecutionEnvironmentSelfCheck {

    fun run(): BaseExecutionEnvironmentSelfCheckReport {
        val cases = mutableListOf<BaseExecutionEnvironmentSelfCheckCase>()
        // Deterministic, filesystem-free stand-ins for the verified base root and
        // its installed executable. The derivation functions asserted below are
        // pure and never touch the device, so these paths never need to exist.
        val baseRoot = File(BASE_ROOT_PREFIX)
        val installedExecutable =
            File(File(baseRoot, INSTALLED_SUBDIR), BaseUserspaceArtifact.EXECUTABLE_FILE)

        cases += caseOf(
            "A. the working directory derives from the fixed name directly under the verified base root",
        ) {
            val derived = BaseExecutionEnvironment.workingDirectory(baseRoot)
            val expected = File(BASE_ROOT_PREFIX, BaseExecutionEnvironment.WORK_DIR_NAME)
            (derived.path == expected.path) to derived.path
        }

        cases += caseOf(
            "A. the working-directory name is the frozen literal 'work' (never user-selectable)",
        ) {
            (BaseExecutionEnvironment.WORK_DIR_NAME == "work") to BaseExecutionEnvironment.WORK_DIR_NAME
        }

        cases += caseOf(
            "B. the derived working directory is strictly inside the verified base root",
        ) {
            val derived = BaseExecutionEnvironment.workingDirectory(baseRoot)
            (BaseExecutionEnvironment.isStrictlyInside(derived.path, baseRoot.path)) to derived.path
        }

        cases += caseOf(
            "B. the installed base executable is strictly inside the verified base root",
        ) {
            (BaseExecutionEnvironment.isStrictlyInside(installedExecutable.path, baseRoot.path)) to
                installedExecutable.path
        }

        cases += caseOf(
            "C. containment never accepts the base root itself as the working directory",
        ) {
            (!BaseExecutionEnvironment.isStrictlyInside(baseRoot.path, baseRoot.path)) to baseRoot.path
        }

        cases += caseOf(
            "C. containment never accepts a sibling of the verified base root",
        ) {
            val siblingChild =
                File(BASE_ROOT_PREFIX + SIBLING_SUFFIX, BaseExecutionEnvironment.WORK_DIR_NAME)
            (!BaseExecutionEnvironment.isStrictlyInside(siblingChild.path, baseRoot.path)) to
                siblingChild.path
        }

        cases += caseOf(
            "D. containment accepts a strict child but never root-or-equal (pure rules)",
        ) {
            val childIn = BaseExecutionEnvironment.isStrictlyInside("/a/b/c", "/a/b")
            val deeperIn = BaseExecutionEnvironment.isStrictlyInside("/a/b/c/d", "/a/b")
            val rootItself = !BaseExecutionEnvironment.isStrictlyInside("/a/b", "/a/b")
            val prefixCollision = !BaseExecutionEnvironment.isStrictlyInside("/a/bb", "/a/b")
            val unrelated = !BaseExecutionEnvironment.isStrictlyInside("/x/y", "/a/b")
            val emptyBase = !BaseExecutionEnvironment.isStrictlyInside("/x", "/")
            (childIn && deeperIn && rootItself && prefixCollision && unrelated && emptyBase) to
                "childIn=$childIn deeperIn=$deeperIn rootItself=$rootItself " +
                "prefixCollision=$prefixCollision unrelated=$unrelated emptyBase=$emptyBase"
        }

        return BaseExecutionEnvironmentSelfCheckReport(cases)
    }

    /** Adds one genuine assertion case; the block returns a `(expectedMet, detail)` pair. */
    private fun caseOf(
        label: String,
        assert: () -> Pair<Boolean, String>,
    ): BaseExecutionEnvironmentSelfCheckCase {
        val (expectedMet, detail) = assert()
        return BaseExecutionEnvironmentSelfCheckCase(label = label, expectedMet = expectedMet, detail = detail)
    }

    private const val BASE_ROOT_PREFIX =
        "/data/data/app.aliasnull/files/aliasnull_base_userspace"
    private const val INSTALLED_SUBDIR = "userspace/base"
    private const val SIBLING_SUFFIX = "_other"
}
