package app.aliasnull.shell.runtime

import app.aliasnull.shell.bootstrap.BaseUserspaceArtifact
import app.aliasnull.shell.runtime.native.LaunchMode
import app.aliasnull.shell.runtime.native.NativeProcessRequest
import java.io.File

/** One policy-decision assertion in a [NativeExecutionPolicySelfCheckReport]. */
internal data class NativeExecutionPolicySelfCheckCase(
    val label: String,
    val expectedMet: Boolean,
    val detail: String,
)

/** Aggregated result of one [NativeExecutionPolicySelfCheck] run. */
internal data class NativeExecutionPolicySelfCheckReport(
    val cases: List<NativeExecutionPolicySelfCheckCase>,
) {
    val allPassed: Boolean
        get() = cases.all { it.expectedMet }

    val passedCount: Int
        get() = cases.count { it.expectedMet }
}

/**
 * Dormant, process-free self-check for the permanent base-executable launch
 * policy (Part 27-S2). Every case makes a GENUINE call to
 * [NativeExecutionPolicy.decide] or [NativeExecutionPolicy.decideBaseExecutable]
 * on a request built the same way the runtime builds it and asserts the true
 * result; nothing is fabricated and no child process is launched (the decision
 * layer never touches the native runner). The assertions fix the permanent
 * [LaunchMode.LINKER_LAUNCH] contract:
 *
 *   - the base executable's request is a LINKER_LAUNCH request whose argv is the
 *     fixed linker host plus the verified installed executable path;
 *   - that exact request is the ONLY thing the base policy allows;
 *   - any other target path, file name, linker argument or launch mode is
 *     rejected;
 *   - the ordinary [NativeExecutionPolicy.decide] gate still allows exactly the
 *     four DIRECT host self-check invocations and never permits a LINKER_LAUNCH
 *     request or a direct exec of the linker, so no generic "run this file
 *     through linker64" facility exists.
 *
 * Readiness and integrity - "the base cannot run before the base userspace is
 * installed and verified" and "bootstrap integrity is enforced" - are not pure
 * policy decisions and are deliberately NOT asserted here: they are enforced by
 * the runtime gate in [AliasNullRuntimeManager.runNativeProcessTest] (NotReady is
 * returned before any execution is attempted) and by
 * [app.aliasnull.shell.bootstrap.BaseUserspaceBootstrap] (digest, ELF and
 * exec-mode validation), and CI asserts the bundled executable's SHA-256.
 *
 * Like [NativeProcessSelfCheck], this object is deliberately NOT wired to any
 * Shell command, UI or startup path; it exists so the codebase (or a future test
 * surface) can verify the decision contract on demand by calling [run]. Every
 * assertion is deterministic and depends on no device state.
 */
internal object NativeExecutionPolicySelfCheck {

    fun run(): NativeExecutionPolicySelfCheckReport {
        val cases = mutableListOf<NativeExecutionPolicySelfCheckCase>()
        // A deterministic stand-in install root + verified executable. The policy
        // decides purely on file name and argv shape and never touches the file
        // system, so these paths never need to exist.
        val verifiedRoot = File(VERIFIED_ROOT_PREFIX, BASE_SUBDIR)
        val verifiedExecutable = File(verifiedRoot, BaseUserspaceArtifact.EXECUTABLE_FILE)
        val baseRequest = NativeProcessTestKind.BASE_USERSPACE_EXECUTABLE.request(verifiedRoot)

        cases += decisionCase(
            "A. the base executable's request is LINKER_LAUNCH with the fixed linker host argv",
        ) {
            val expectedArgv = listOf(
                NativeExecutionPolicy.LINKER64_PATH,
                verifiedExecutable.absolutePath,
            )
            (baseRequest.launchMode == LaunchMode.LINKER_LAUNCH &&
                baseRequest.argv == expectedArgv
                ) to "mode=${baseRequest.launchMode} argv='${display(baseRequest.argv)}'"
        }

        cases += decisionCase(
            "B. the base policy allows exactly the verified base executable's LINKER_LAUNCH request",
        ) {
            val decision = NativeExecutionPolicy.decideBaseExecutable(baseRequest, verifiedExecutable)
            (decision is NativeExecutionPolicyDecision.Allowed) to describe(decision)
        }

        cases += decisionCase(
            "C. the base policy rejects an unexpected target file name",
        ) {
            val other = File(verifiedRoot, "not-" + BaseUserspaceArtifact.EXECUTABLE_FILE)
            val decision = NativeExecutionPolicy.decideBaseExecutable(baseRequest, other)
            isRejected(decision, NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED) to describe(decision)
        }

        cases += decisionCase(
            "C. the base policy pins the target to the verified installed executable path",
        ) {
            // Same file name but under a root that is not the verified root, so the
            // request argv (built for the verified root) cannot match its path.
            val foreign = File(FOREIGN_ROOT_PREFIX, BASE_SUBDIR)
            val foreignExecutable = File(foreign, BaseUserspaceArtifact.EXECUTABLE_FILE)
            val decision = NativeExecutionPolicy.decideBaseExecutable(baseRequest, foreignExecutable)
            isRejected(decision, NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED) to describe(decision)
        }

        cases += decisionCase(
            "D. the base policy rejects an arbitrary linker target",
        ) {
            val rogue = NativeProcessRequest(
                argv = listOf(NativeExecutionPolicy.LINKER64_PATH, "/some/arbitrary/target"),
                launchMode = LaunchMode.LINKER_LAUNCH,
            )
            val decision = NativeExecutionPolicy.decideBaseExecutable(rogue, verifiedExecutable)
            isRejected(decision, NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED) to describe(decision)
        }

        cases += decisionCase(
            "E. the base policy rejects arbitrary user linker arguments",
        ) {
            val extraArg = NativeProcessRequest(
                argv = listOf(
                    NativeExecutionPolicy.LINKER64_PATH,
                    verifiedExecutable.absolutePath,
                    "user-injected-argument",
                ),
                launchMode = LaunchMode.LINKER_LAUNCH,
            )
            val decision = NativeExecutionPolicy.decideBaseExecutable(extraArg, verifiedExecutable)
            isRejected(decision, NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED) to describe(decision)
        }

        cases += decisionCase(
            "E. the base policy requires the base executable's defined LINKER_LAUNCH mode",
        ) {
            // Same argv as the verified request but mis-declared as DIRECT: the mode
            // is not a separate trust input, so the request must be rejected.
            val wrongMode = NativeProcessRequest(
                argv = NativeExecutionPolicy.baseExecutableInvocation(verifiedRoot),
                launchMode = LaunchMode.DIRECT,
            )
            val decision = NativeExecutionPolicy.decideBaseExecutable(wrongMode, verifiedExecutable)
            isRejected(decision, NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED) to describe(decision)
        }

        cases += decisionCase(
            "F. the ordinary policy never permits a LINKER_LAUNCH request (no generic linker launcher)",
        ) {
            val decision = NativeExecutionPolicy.decide(baseRequest)
            isRejected(decision, NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED) to describe(decision)
        }

        cases += decisionCase(
            "F. the ordinary policy never execs the system linker directly",
        ) {
            val directLinker = NativeProcessRequest(
                argv = listOf(
                    NativeExecutionPolicy.LINKER64_PATH,
                    verifiedExecutable.absolutePath,
                ),
            )
            val decision = NativeExecutionPolicy.decide(directLinker)
            isRejected(decision, NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED) to describe(decision)
        }

        cases += decisionCase(
            "G. the ordinary DIRECT allowlist is unchanged: the four host self-check invocations all pass",
        ) {
            val allowed = NativeExecutionPolicy.permittedInvocations.all { argv ->
                val decision = NativeExecutionPolicy.decide(NativeProcessRequest(argv = argv))
                decision is NativeExecutionPolicyDecision.Allowed
            }
            val count = NativeExecutionPolicy.permittedInvocations.size
            (allowed && count == HOST_CASE_COUNT) to "permittedInvocations=$count"
        }

        cases += decisionCase(
            "H. policy continues to reject arbitrary shell, non-allowlisted args and malformed requests",
        ) {
            val shell = NativeExecutionPolicy.decide(
                NativeProcessRequest(argv = listOf("/system/bin/sh", "-c", "echo must-not-run")),
            )
            val badArgs = NativeExecutionPolicy.decide(
                NativeProcessRequest(argv = listOf("/system/bin/echo", "not-the-allowlisted-token")),
            )
            val malformed = NativeExecutionPolicy.decide(NativeProcessRequest(argv = listOf("")))
            val shellRejected =
                isRejected(shell, NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED)
            val argsRejected = isRejected(badArgs, NativeExecutionRejectionCode.ARGUMENTS_NOT_PERMITTED)
            val malformedRejected = isRejected(malformed, NativeExecutionRejectionCode.INVALID_REQUEST)
            (shellRejected && argsRejected && malformedRejected) to buildString {
                append("shell=").append(describe(shell)).append("; ")
                append("args=").append(describe(badArgs)).append("; ")
                append("malformed=").append(describe(malformed))
            }
        }

        return NativeExecutionPolicySelfCheckReport(cases)
    }

    /** Adds one genuine assertion case; the block returns a `(expectedMet, detail)` pair. */
    private fun decisionCase(
        label: String,
        assert: () -> Pair<Boolean, String>,
    ): NativeExecutionPolicySelfCheckCase {
        val (expectedMet, detail) = assert()
        return NativeExecutionPolicySelfCheckCase(label = label, expectedMet = expectedMet, detail = detail)
    }

    private fun isRejected(
        decision: NativeExecutionPolicyDecision,
        code: NativeExecutionRejectionCode,
    ): Boolean =
        decision is NativeExecutionPolicyDecision.Rejected && decision.reason.code == code

    private fun describe(decision: NativeExecutionPolicyDecision): String = when (decision) {
        is NativeExecutionPolicyDecision.Allowed -> "Allowed"
        is NativeExecutionPolicyDecision.Rejected -> "Rejected ${decision.reason.code}: ${decision.reason.message}"
    }

    private fun display(argv: List<String>): String = argv.joinToString(" ")

    private const val HOST_CASE_COUNT = 4
    private const val BASE_SUBDIR = "userspace/base"
    private const val VERIFIED_ROOT_PREFIX =
        "/data/data/app.aliasnull/files/aliasnull_base_userspace"
    private const val FOREIGN_ROOT_PREFIX =
        "/data/data/app.aliasnull/files/some-other-unverified-root"
}
