package app.aliasnull.shell.bootstrap

/**
 * The persisted installation state of the AliasNull base userspace
 * (Part 27-R bootstrap metadata model).
 *
 * The four states keep the honest record required by the bootstrap contract:
 *
 *   - [NOT_INSTALLED]: no successful install has ever been recorded. This is the
 *     default when the metadata file is absent.
 *   - [INSTALLING]: an install attempt is running (or crashed mid-attempt). An
 *     INSTALLING record is never treated as success: on the next run the target
 *     tree is re-validated and the attempt is either completed or re-run.
 *   - [INSTALLED]: a full install completed AND the installed tree was validated
 *     AND this state was written only after validation. Never inferred from the
 *     mere existence of a directory.
 *   - [FAILED]: the most recent attempt completed without a valid install. The
 *     runtime does not claim base-userspace readiness and retry may re-attempt.
 *
 * The state is persisted alongside the installed artifact version so the
 * bootstrap can distinguish "same artifact already installed" from "different
 * version available" without re-reading the whole tree every launch.
 */
enum class BaseUserspaceBootstrapState {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    FAILED,
}
