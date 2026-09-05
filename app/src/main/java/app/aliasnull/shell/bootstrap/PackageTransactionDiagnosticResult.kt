package app.aliasnull.shell.bootstrap

/**
 * The structured outcome of one Part 27-V-DEVICE-DIAGNOSTIC case run by
 * [PackageTransactionDiagnostic] against the real [PackageTransaction] engine on
 * a dedicated disposable app-private root.
 *
 * [passed] is true ONLY when the transaction's genuine structured outcome and the
 * inspected postconditions matched the case's stated expectation
 * ([PackageTransactionTestKind.expectedOutcome]) - never merely because a
 * function returned without throwing. [outcomeLine] is the short, truthful
 * headline of what actually happened ("INSTALL SUCCEEDED state=INSTALLED" or
 * "INSTALL REJECTED DIGEST MISMATCH"); a rejected case's headline does not invent
 * wording, it reflects the engine result category the scenario targeted.
 * [detailLines] carries the verifiable facts: for a successful install the
 * package name, version, arch, state and installed path; for a rejection the
 * engine's actual category, phase and message plus the checked postconditions
 * (no live package, no INSTALLED state, staging cleaned). No stack trace is ever
 * dumped into the normal UI; a genuinely unexpected throw surfaces as a FAIL
 * result whose message names the error in one line.
 *
 * Public because this type crosses the runtime -> UI boundary through the public
 * [app.aliasnull.shell.runtime.ShellRuntimeManager] seam and public Shell UI
 * state (mirroring the public
 * [app.aliasnull.shell.runtime.NativeProcessTestResult]); the underlying
 * orchestrator [PackageTransactionDiagnostic] stays internal.
 */
data class PackageTransactionDiagnosticResult(
    val kind: PackageTransactionTestKind,
    val passed: Boolean,
    val outcomeLine: String,
    val detailLines: List<String> = emptyList(),
)
