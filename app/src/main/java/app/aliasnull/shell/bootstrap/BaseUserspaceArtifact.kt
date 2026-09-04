package app.aliasnull.shell.bootstrap

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The single source of truth describing the bundled AliasNull base-userspace
 * artifact (Part 27-R).
 *
 * The artifact is a small, deterministic, versioned set of original
 * AliasNull-authored metadata files (see [PROVENANCE_FILE]) bundled verbatim
 * under [ASSET_DIR] in the signed APK. It deliberately contains NO executables
 * and is not a Linux filesystem: no arm64 tool that is genuinely required for
 * the base runtime can yet be produced reproducibly from this repository, so
 * none is bundled or faked. It only establishes the versioned, verified base
 * that a future package manager and optional components (Python, Git, tools)
 * build upon.
 *
 * [FILES] maps each artifact-relative file name to its expected SHA-256 digest
 * (lowercase hex). The digests are the compile-time-known integrity record the
 * bootstrap verifies the bundled asset and the installed copy against, so an
 * altered or truncated file can never be mistaken for a valid one. When a file
 * in the artifact changes, its digest here must be regenerated from the exact
 * committed bytes (the build does not compute it), keeping the record
 * non-circular and reproducible.
 */
object BaseUserspaceArtifact {

    /**
     * The base artifact semantic version. Bump it whenever the artifact content
     * changes so the bootstrap treats an older installed base as upgradeable
     * rather than current. Mirrored by the [VERSION_FILE] file.
     */
    const val VERSION = "1"

    /**
     * The single architecture this build is produced for (ABI filter arm64-v8a).
     * A future tool installer asserts compatibility against this marker.
     */
    const val ARCH = "arm64-v8a"

    /** Asset directory (under the APK) holding the bundled artifact files. */
    const val ASSET_DIR = "userspace/base"

    /** Artifact-relative file name carrying [VERSION]. */
    const val VERSION_FILE = "VERSION"

    /** Artifact-relative file name carrying [ARCH]. */
    const val ARCH_FILE = "ARCH"

    /** Artifact-relative file name that explains the artifact and its scope. */
    const val DESCRIPTION_FILE = "DESCRIPTION"

    /** Artifact-relative provenance document (provenance of every included file). */
    const val PROVENANCE_FILE = "PROVENANCE.txt"

    /** Artifact-relative license note. */
    const val LICENSE_FILE = "LICENSE.txt"

    /**
     * Every artifact file and its expected SHA-256 digest (lowercase hex),
     * computed from the exact bytes committed under [ASSET_DIR]. Insertion order
     * is the stable extraction order. Do not hand-edit digests: regenerate them
     * from the file bytes whenever a file's content changes.
     */
    val FILES: Map<String, String> = linkedMapOf(
        VERSION_FILE to "4355a46b19d348dc2f57c046f8ef63d4538ebb936000f3c9ee954a27460dd865",
        ARCH_FILE to "7f10c3cd4593d1d6ded27d658e7c05216011c955c200aac551caad0c979d4d90",
        DESCRIPTION_FILE to "d3e6a5dfcf48c2533747fbd7de3316e24db2a441276f6a752706c4204586eb07",
        PROVENANCE_FILE to "3aec6ccc37aecff4efa45a6f575e1d18136d884708f56228126253807df73f0f",
        LICENSE_FILE to "7e3d3c7d699c7cb35aa2f094ea5750a3a8cd13273053fe5541bd95b69be711ae",
    )

    /**
     * The artifact-relative file names that must exist and be integrity-valid for
     * an install to count as valid. Equal to [FILES] today; kept distinct so a
     * future artifact can carry optional extra files without making them
     * load-bearing.
     */
    val REQUIRED_FILE_PATHS: List<String> = FILES.keys.toList()

    /** Asset path (full, slash-separated) for [relative], for opening via AssetManager. */
    fun assetPath(relative: String): String = "$ASSET_DIR/$relative"
}
