package app.aliasnull.shell.bootstrap

/**
 * The authorized Part 27-V-DEVICE-DIAGNOSTIC cases a developer diagnostic panel
 * may run against the real [PackageTransaction] engine.
 *
 * This enum is the ONLY choice a caller (a ViewModel, a button) may make. There is
 * deliberately no way to pick a package name, a manifest, a payload, an
 * architecture, a source path or any user-supplied value here: every case maps to
 * exactly one internally generated, deterministic scenario owned by
 * [PackageTransactionDiagnostic], and a press runs that scenario against a
 * dedicated disposable app-private root through the real transaction engine. The
 * UI labels come from [title]; [expectedOutcome] is the canonical phrase the case
 * must produce to be a PASS, shown next to the result exactly as it reads here.
 *
 * Each case exercises the real transaction path - manifest parsing, source
 * validation, exact-tree validation, staging, streamed SHA-256 copy, staged
 * verification, the `.verified` marker, dependency/conflict checks, the
 * target-absent check, atomic promotion, live-tree verification, the INSTALLED
 * state commit and cleanup - for whichever part of that pipeline the scenario
 * targets. Nothing is simulated and no result is faked: a case is PASS only when
 * the transaction's genuine structured outcome and the postconditions match
 * [expectedOutcome].
 */
internal enum class PackageTransactionTestKind(
    val title: String,
    val expectedOutcome: String,
) {
    /** Test A: a valid data-only package installs and commits INSTALLED state. */
    VALID_INSTALL(
        "Valid Install",
        "INSTALL SUCCEEDED state=INSTALLED",
    ),

    /** Test B: a second install of the same package is refused, first untouched. */
    REPEAT_INSTALL(
        "Repeat Install",
        "INSTALL REJECTED TARGET ALREADY EXISTS",
    ),

    /** Test C: a payload whose bytes do not match the declared SHA-256 is refused. */
    DIGEST_FAILURE(
        "Digest Failure",
        "INSTALL REJECTED DIGEST MISMATCH",
    ),

    /** Test D: a manifest-declared payload that is absent from the source is refused. */
    MISSING_PAYLOAD(
        "Missing Payload",
        "INSTALL REJECTED MISSING PAYLOAD",
    ),

    /** Test E: an undeclared payload file in the source is refused. */
    EXTRA_PAYLOAD(
        "Extra Payload",
        "INSTALL REJECTED EXTRA PAYLOAD",
    ),

    /** Test F: a dependency on an intentionally absent exact package is refused. */
    DEPENDENCY_FAILURE(
        "Dependency Failure",
        "INSTALL REJECTED DEPENDENCY NOT SATISFIED",
    ),

    /** Test G: an exact conflict with an installed package is refused. */
    CONFLICT_FAILURE(
        "Conflict Failure",
        "INSTALL REJECTED CONFLICT",
    ),

    /** Test H: an exec-flagged payload is stored as data - not chmod'd, not launched. */
    EXEC_FLAG_INERT(
        "Exec Flag Inertness",
        "INSTALL SUCCEEDED exec metadata remained inert",
    ),
}
