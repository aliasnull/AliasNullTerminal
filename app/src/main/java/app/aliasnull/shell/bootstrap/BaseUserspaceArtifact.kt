package app.aliasnull.shell.bootstrap

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The single source of truth describing the bundled AliasNull base-userspace
 * artifact (Part 27-R, extended by Part 27-S2 and Part 27-T1).
 *
 * The artifact is a small, deterministic, versioned set of original
 * AliasNull-authored metadata files (see [PROVENANCE_FILE]) bundled verbatim
 * under [ASSET_DIR] in the signed APK, PLUS one real executable
 * ([EXECUTABLE_FILE], Part 27-S2). It is not a Linux filesystem, a shell or a
 * set of system tools: the executable is the first genuine arm64 component of
 * the base userspace and is exercised only through the controlled developer
 * diagnostic, never through the Shell. Since Part 27-T1 the same executable has
 * a controlled execution-environment mode (selected by the single AliasNull
 * environment override) that reports the real controlled working directory and
 * override; see [NativeExecutionPolicy] and the DESCRIPTION/PROVENANCE asset.
 *
 * [FILES] maps each artifact-relative file name to its expected SHA-256 digest
 * (lowercase hex). The digests are the compile-time-known integrity record the
 * bootstrap verifies the bundled asset and the installed copy against, so an
 * altered or truncated file can never be mistaken for a valid one. When a file
 * in the artifact changes, its digest here must be regenerated from the exact
 * committed bytes (the build does not compute it), keeping the record
 * non-circular and reproducible. The executable's digest is
 * [BASE_EXECUTABLE_SHA256]; its provenance (source revision, CI run, toolchain)
 * is recorded in [Executable] so the manifest entry is never an unexplained
 * hash.
 */
object BaseUserspaceArtifact {

    /**
     * The base artifact semantic version. Bump it whenever the artifact content
     * changes so the bootstrap treats an older installed base as upgradeable
     * rather than current. Mirrored by the [VERSION_FILE] file.
     */
    const val VERSION = "3"

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
     * The bundled base-userspace executable (Part 27-S2): a real 64-bit AArch64
     * Android PIE built from [Executable.SOURCE_FILE] by the CI workflow
     * recorded in [Executable], committed verbatim under [ASSET_DIR]. It is the
     * only artifact file that is an executable, and the only one the bootstrap
     * additionally restores execute permission on and verifies as a 64-bit
     * AArch64 ELF before it reports INSTALLED.
     */
    const val EXECUTABLE_FILE = "aliasnull_base_probe"

    /**
     * Expected SHA-256 (lowercase hex) of the exact [EXECUTABLE_FILE] bytes
     * committed under [ASSET_DIR]. Single source of truth for the executable's
     * integrity: the CI workflow compares a fresh source rebuild against this
     * value (and against the committed bytes) so the committed executable is
     * proven regenerable, never merely trusted.
     */
    const val BASE_EXECUTABLE_SHA256 =
        "bda533cbfa75ab4017320016bbdaa772279c4632d59324a3579ebf9f05128fdd"

    /**
     * Traceable provenance of the bundled [EXECUTABLE_FILE]. Kept beside the
     * digest so the manifest entry establishes source, build toolchain,
     * architecture and revision rather than being an unexplained hash.
     */
    object Executable {
        /** Semantic version of the executable component itself. */
        const val VERSION = "1"

        /** Repository-relative source file the executable is built from. */
        const val SOURCE_FILE = "app/src/main/cpp/aliasnull_base_probe.cpp"

        /** Source revision the committed executable bytes were built at. */
        const val SOURCE_REVISION = "51deebfb7a33bf8479fdd8e77bd17ae05b8e9e8c"

        /** CI workflow run id that produced the committed executable bytes. */
        const val SOURCE_CI_RUN_ID = "33951789760"

        /** CI workflow name that produced the committed executable bytes. */
        const val SOURCE_CI_WORKFLOW = "Build AliasNull Android"

        /** Android NDK version used to build the committed executable. */
        const val BUILD_NDK = "28.2.13676358"

        /** Android API level the executable targets. */
        const val TARGET_ANDROID_API = "26"

        /** The executable's deterministic single-line stdout, then exit 0. */
        const val OUTPUT_STRING = "AliasNull base userspace OK"
    }

    /** True only for the single bundled executable file. */
    fun isExecutableFile(relative: String): Boolean = relative == EXECUTABLE_FILE

    /**
     * Every artifact file and its expected SHA-256 digest (lowercase hex),
     * computed from the exact bytes committed under [ASSET_DIR]. Insertion order
     * is the stable extraction order. Do not hand-edit digests: regenerate them
     * from the file bytes whenever a file's content changes.
     */
    val FILES: Map<String, String> = linkedMapOf(
        VERSION_FILE to "1121cfccd5913f0a63fec40a6ffd44ea64f9dc135c66634ba001d10bcf4302a2",
        ARCH_FILE to "7f10c3cd4593d1d6ded27d658e7c05216011c955c200aac551caad0c979d4d90",
        DESCRIPTION_FILE to "77c4729ba6d57b3bda949e70b389fcd82f0c80d2ad97c1bd741a9ed3eab73fad",
        PROVENANCE_FILE to "87c24cc160b675d3374c1c0c21b6a64e353c43d6edaa02c296ebfbada7d86265",
        LICENSE_FILE to "7e3d3c7d699c7cb35aa2f094ea5750a3a8cd13273053fe5541bd95b69be711ae",
        EXECUTABLE_FILE to BASE_EXECUTABLE_SHA256,
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
