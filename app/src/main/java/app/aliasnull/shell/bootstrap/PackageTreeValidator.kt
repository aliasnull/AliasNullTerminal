package app.aliasnull.shell.bootstrap

import java.io.File
import java.security.MessageDigest

/**
 * Reusable, arbitrary-manifest package-tree validation helper of the Part 27-V
 * data-only package transaction.
 *
 * One routine validates ANY directory tree against ONE [PackageManifest] as an
 * exact package tree: the tree must contain the reserved canonical
 * [PackageLayout.RESERVED_MANIFEST_FILE], whose bytes must parse to a manifest
 * byte-identical to the expected canonical manifest, plus exactly the manifest's
 * payload regular files (each matching its declared SHA-256) and the directories
 * required to contain them - nothing else. This is the same single source of
 * truth used for the source tree (PHASE A), the fully staged tree (PHASE D), the
 * promoted live tree (post-promotion live verification and crash recovery) and
 * the self-check's scratch trees, so no two call sites can drift.
 *
 * The validator is strict about object roles, mirroring the staged-tree contract:
 *
 *   - the package manifest is [PackageLayout.RESERVED_MANIFEST_FILE];
 *   - package payload is exactly the manifest's declared files;
 *   - the transaction marker [PackageLayout.VERIFIED_MARKER_FILE] is allowed at
 *     the tree root ONLY when [allowVerifiedMarker] is true, and is reported
 *     separately - it is never payload and never required for validity;
 *   - anything else (a missing payload file, an extra file or directory, a
 *     symlink, a special object, a declared-file/required-directory collision, a
 *     reserved-name collision, an unsafe path) is a concrete finding.
 *
 * Type decisions are made from the real `lstat` mode ([BaseUserspaceFiles.modeBits])
 * exactly like the base userspace helpers, so a symbolic link is reported as the
 * link itself and never mistaken for its target, and a symlinked ancestor is
 * never followed (the walker descends only into real directories). Reads only;
 * never mutates [root]. The SHA-256 comparison streams the file in bounded
 * chunks, so large payloads are not held in memory.
 *
 * The report's `valid` is true only when the manifest is present and canonical,
 * the payload is complete and digest-correct, every object is a real regular
 * file under a real directory chain, no unexpected object exists and no
 * structural or reserved-name violation occurred. Marker presence is observed
 * and reported but never required.
 */
internal object PackageTreeValidator {

    /** Mirror of the POSIX file-type masks (see [BaseUserspaceFiles]). */
    private const val S_IFMT = 0xF000
    private const val S_IFREG = 0x8000
    private const val S_IFDIR = 0x4000
    private const val S_IFLNK = 0xA000

    private const val READ_CHUNK_BYTES = 65536

    /** The outcome of one [validate] call; each list names concrete offenders. */
    internal data class Report(
        val manifestPresent: Boolean,
        val manifestIsRegularFile: Boolean,
        val manifestParses: Boolean,
        val manifestIdentityMatches: Boolean,
        val missingFiles: List<String> = emptyList(),
        val typeErrors: List<String> = emptyList(),
        val digestMismatches: List<String> = emptyList(),
        val unexpectedObjects: List<String> = emptyList(),
        val unsafePaths: List<String> = emptyList(),
        val structuralErrors: List<String> = emptyList(),
        val markerPresent: Boolean = false,
    ) {
        val valid: Boolean
            get() = manifestPresent && manifestIsRegularFile && manifestParses &&
                manifestIdentityMatches && missingFiles.isEmpty() && typeErrors.isEmpty() &&
                digestMismatches.isEmpty() && unexpectedObjects.isEmpty() &&
                unsafePaths.isEmpty() && structuralErrors.isEmpty()
    }

    /**
     * Validates [root] as an exact tree for [expected]. [allowVerifiedMarker]
     * permits the transaction marker at the tree root (a staged tree awaiting
     * promotion, or a just-promoted live tree whose marker has not yet been
     * cleaned); when false the marker, if present, is an unexpected object.
     */
    fun validate(root: File, expected: PackageManifest, allowVerifiedMarker: Boolean): Report {
        val manifestFile = File(root, PackageLayout.RESERVED_MANIFEST_FILE)
        val manifestMode = BaseUserspaceFiles.modeBits(manifestFile)
        val manifestPresent = manifestMode != null
        val manifestIsRegularFile = manifestMode != null && (manifestMode and S_IFMT) == S_IFREG

        var manifestParses = false
        var manifestIdentityMatches = false
        if (manifestPresent && manifestIsRegularFile) {
            val bytes = readBytesQuiet(manifestFile)
            if (bytes != null) {
                val parsed = runCatching {
                    PackageManifest.parse(bytes.toString(Charsets.UTF_8))
                }.getOrNull()
                manifestParses = parsed != null
                manifestIdentityMatches =
                    parsed != null && bytes.contentEquals(expected.canonicalBytes)
            }
        }

        val missing = mutableListOf<String>()
        val typeErrors = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        val unsafePaths = mutableListOf<String>()
        val structural = mutableListOf<String>()

        // Structural checks over the declared payload list alone (pure; no disk).
        val payloadPaths = expected.files.map { it.path }
        val pathSet = payloadPaths.toSet()
        if (payloadPaths.contains(PackageLayout.RESERVED_MANIFEST_FILE)) {
            structural += "payload path '${PackageLayout.RESERVED_MANIFEST_FILE}' collides with the reserved manifest"
        }
        for (path in payloadPaths) {
            val first = path.substringBefore('/')
            // A reserved file lives at the package root, so a payload path that
            // IS the reserved name or sits under it would collide with that file.
            if (first == PackageLayout.RESERVED_MANIFEST_FILE && path != PackageLayout.RESERVED_MANIFEST_FILE) {
                structural += "payload path '$path' collides with the reserved manifest file at the package root"
            }
            if (first == PackageLayout.VERIFIED_MARKER_FILE) {
                structural += "payload path '$path' collides with the reserved transaction marker name at the package root"
            }
        }
        for (path in payloadPaths) {
            ancestorsOf(path).forEach { ancestor ->
                if (ancestor in pathSet) {
                    structural +=
                        "payload path '$path' requires '${ancestor}' as a directory but '${ancestor}' is itself a payload file"
                }
            }
        }

        // Per-payload on-disk checks.
        for (entry in expected.files) {
            val relative = entry.path
            if (!BaseUserspaceFiles.isSafeRelativePath(relative)) {
                unsafePaths += relative
                continue
            }
            val file = File(root, relative)
            val mode = BaseUserspaceFiles.modeBits(file)
            if (mode == null) {
                missing += relative
                continue
            }
            if ((mode and S_IFMT) == S_IFLNK) {
                typeErrors += "$relative is a symbolic link"
                continue
            }
            if ((mode and S_IFMT) != S_IFREG) {
                typeErrors += "$relative is not a regular file"
                continue
            }
            if (unsafeAncestor(root, relative)) {
                typeErrors += "$relative has a symbolic-link or non-directory ancestor"
                continue
            }
            val actual = sha256Hex(file)
            if (actual == null) {
                typeErrors += "$relative could not be read"
            } else if (actual != entry.sha256) {
                mismatched += relative
            }
        }

        // Unexpected-object detection over the real tree.
        val requiredDirectories = payloadPaths.flatMap { ancestorsOf(it) }.toSet()
        val markerSet: Set<String> =
            if (allowVerifiedMarker) setOf(PackageLayout.VERIFIED_MARKER_FILE) else emptySet()
        val expectedFiles =
            payloadPaths.toSet() + PackageLayout.RESERVED_MANIFEST_FILE + markerSet
        val unexpected = mutableListOf<String>()
        var markerPresent = false
        val children = root.listFiles()
        if (children != null) {
            collectUnexpected(
                dir = root,
                prefix = "",
                expectedFiles = expectedFiles,
                requiredDirectories = requiredDirectories,
                unexpected = unexpected,
            ) { relative, mode ->
                if ((mode and S_IFMT) == S_IFREG) {
                    if (relative == PackageLayout.VERIFIED_MARKER_FILE) markerPresent = true
                }
            }
        }

        return Report(
            manifestPresent = manifestPresent,
            manifestIsRegularFile = manifestIsRegularFile,
            manifestParses = manifestParses,
            manifestIdentityMatches = manifestIdentityMatches,
            missingFiles = missing.sorted(),
            typeErrors = typeErrors.sorted(),
            digestMismatches = mismatched.sorted(),
            unexpectedObjects = unexpected.sorted(),
            unsafePaths = unsafePaths.sorted(),
            structuralErrors = structural.sorted(),
            markerPresent = markerPresent,
        )
    }

    /**
     * Walks the real tree under [dir] (which is [root] initially) and appends
     * every object that is not an expected payload file, the reserved manifest,
     * or a directory required to contain a payload file. Real directories are
     * descended; symbolic links and special objects are never followed. [observe]
     * is invoked for every real regular file so the caller can note the marker.
     */
    private fun collectUnexpected(
        dir: File,
        prefix: String,
        expectedFiles: Set<String>,
        requiredDirectories: Set<String>,
        unexpected: MutableList<String>,
        observe: (String, Int) -> Unit,
    ) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            val mode = BaseUserspaceFiles.modeBits(child) ?: continue
            val type = mode and S_IFMT
            when (type) {
                S_IFREG -> {
                    observe(relative, mode)
                    if (relative !in expectedFiles) unexpected += relative
                }
                S_IFDIR -> {
                    if (relative !in requiredDirectories) unexpected += relative
                    collectUnexpected(child, relative, expectedFiles, requiredDirectories, unexpected, observe)
                }
                else -> {
                    // A symlink, FIFO, socket or device node anywhere in the tree
                    // is never payload and never traversed.
                    unexpected += relative
                }
            }
        }
    }

    /** True when any ancestor of [relative] under [root] is a symbolic link or
     * not a real directory (a containment/type guard against symlink escape). */
    private fun unsafeAncestor(root: File, relative: String): Boolean {
        val segments = relative.split('/')
        var current = root
        for (index in 0 until segments.size - 1) {
            current = File(current, segments[index])
            val mode = BaseUserspaceFiles.modeBits(current) ?: return true
            if ((mode and S_IFMT) != S_IFDIR) return true
        }
        return false
    }

    /** The directory ancestors of a safe relative payload [path] ("" excluded). */
    private fun ancestorsOf(path: String): List<String> {
        val result = mutableListOf<String>()
        val segments = path.split('/')
        val builder = StringBuilder()
        for (index in 0 until segments.size - 1) {
            if (index > 0) builder.append('/')
            builder.append(segments[index])
            result.add(builder.toString())
        }
        return result
    }

    /** Lowercase-hex SHA-256 of [file] streamed in bounded chunks, or null. */
    fun sha256Hex(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrNull()

    /** Reads [file]'s bytes, or null when absent/unreadable. */
    fun readBytesQuiet(file: File): ByteArray? =
        runCatching { file.readBytes() }.getOrNull()
}
