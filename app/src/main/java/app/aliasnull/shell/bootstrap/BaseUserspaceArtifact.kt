package app.aliasnull.shell.bootstrap

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The single source of truth describing the bundled AliasNull base-userspace
 * artifact (Part 27-R, extended by Part 27-S2, Part 27-T1 and Part 27-T2).
 *
 * The artifact is a small, deterministic, versioned set of original
 * AliasNull-authored metadata files (see [PROVENANCE_FILE]) bundled verbatim
 * under [ASSET_DIR] in the signed APK, PLUS two real executables
 * ([EXECUTABLE_FILE], Part 27-S2, and [DIGEST_EXECUTABLE_FILE], Part 27-T2). It
 * is not a Linux filesystem, a shell or a set of system tools: the executables
 * are the genuine arm64 components of the base userspace and are exercised only
 * through the controlled developer diagnostic, never through the Shell. Since
 * Part 27-T1 the probe executable has a controlled execution-environment mode
 * (selected by the single AliasNull environment override) that reports the real
 * controlled working directory and override; since Part 27-T2 the digest
 * executable is the first reusable real userspace component - a read-only,
 * Bionic-only SHA-256 file-digest tool whose controlled mode re-verifies the
 * installed base manifest from inside the userspace. See [NativeExecutionPolicy]
 * and the DESCRIPTION/PROVENANCE asset.
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
    const val VERSION = "4"

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

    /**
     * The bundled base-userspace SHA-256 file-digest component (Part 27-T2): a
     * real 64-bit AArch64 Android PIE built from [Digest.SOURCE_FILE] by the CI
     * workflow recorded in [Digest], committed verbatim under [ASSET_DIR]. It is
     * the artifact's second executable (and its first genuinely reusable real
     * userspace component): a dependency-free, read-only file-digest tool whose
     * SHA-256 comes from a FIPS 180-4 implementation authored in the source, not
     * from any cryptographic library. It is launched only through the same
     * [NativeExecutionPolicy] LINKER_LAUNCH model and exercised only by the
     * controlled Base Digest diagnostic, never through the Shell.
     */
    const val DIGEST_EXECUTABLE_FILE = "aliasnull_digest"

    /**
     * Expected SHA-256 (lowercase hex) of the exact [DIGEST_EXECUTABLE_FILE]
     * bytes committed under [ASSET_DIR]. Single source of truth for the digest
     * executable's integrity: the CI workflow compares a fresh source rebuild
     * against this value (and against the committed bytes) so the committed
     * executable is proven regenerable, never merely trusted. Regenerated from a
     * freshly built artifact; never hand-invented.
     */
    const val BASE_DIGEST_SHA256 =
        "0000000000000000000000000000000000000000000000000000000000000000"

    /**
     * Traceable provenance of the bundled [DIGEST_EXECUTABLE_FILE]. Kept beside
     * the digest so the manifest entry establishes source, build toolchain,
     * architecture and revision rather than being an unexplained hash.
     */
    object Digest {
        /** Semantic version of the digest component itself. */
        const val VERSION = "1"

        /** Repository-relative source file the executable is built from. */
        const val SOURCE_FILE = "app/src/main/cpp/aliasnull_digest.cpp"

        /** Source revision the committed executable bytes were built at. */
        const val SOURCE_REVISION = "0000000000000000000000000000000000000000"

        /** CI workflow run id that produced the committed executable bytes. */
        const val SOURCE_CI_RUN_ID = "00000000000"

        /** CI workflow name that produced the committed executable bytes. */
        const val SOURCE_CI_WORKFLOW = "Build AliasNull Android"

        /** Android NDK version used to build the committed executable. */
        const val BUILD_NDK = "28.2.13676358"

        /** Android API level the executable targets. */
        const val TARGET_ANDROID_API = "26"

        /**
         * The deterministic fixed file set the controlled mode hashes, exactly
         * matching [FILES] insertion order (the digest component mirrors this in
         * its source; the Base Digest diagnostic asserts the two agree).
         */
        const val CONTROLLED_FILE_COUNT = 7
    }

    /** True for the two bundled executable files. */
    fun isExecutableFile(relative: String): Boolean =
        relative == EXECUTABLE_FILE || relative == DIGEST_EXECUTABLE_FILE

    /**
     * Every artifact file and its expected SHA-256 digest (lowercase hex),
     * computed from the exact bytes committed under [ASSET_DIR]. Insertion order
     * is the stable extraction order. Do not hand-edit digests: regenerate them
     * from the file bytes whenever a file's content changes.
     */
    val FILES: Map<String, String> = linkedMapOf(
        VERSION_FILE to "7de1555df0c2700329e815b93b32c571c3ea54dc967b89e81ab73b9972b72d1d",
        ARCH_FILE to "7f10c3cd4593d1d6ded27d658e7c05216011c955c200aac551caad0c979d4d90",
        DESCRIPTION_FILE to "b51190450bf0df8d026ef3c87f7cfc39f259ed671f7ce5eb1d6de6398e4e9cd1",
        PROVENANCE_FILE to "ae7335c018e79ea4a1952732b574916483f945af96f5930769779bf0d5a53ff0",
        LICENSE_FILE to "67b2a008b8992986a664ac291d828defbe696480340db2da248581e5634c9230",
        EXECUTABLE_FILE to BASE_EXECUTABLE_SHA256,
        DIGEST_EXECUTABLE_FILE to BASE_DIGEST_SHA256,
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
