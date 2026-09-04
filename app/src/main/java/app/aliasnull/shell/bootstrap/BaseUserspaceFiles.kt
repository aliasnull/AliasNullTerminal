package app.aliasnull.shell.bootstrap

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Result of validating one directory as an installed base-userspace tree.
 *
 * [valid] is true only when every file in the manifest is present AND its bytes
 * match the expected digest AND the [VERSION]/[ARCH] markers match the bundled
 * artifact's expected values. The per-file lists make the failure concrete so a
 * caller (the runtime gate, a diagnostic) can say what is wrong instead of only
 * that something is.
 */
data class BaseUserspaceTreeValidation(
    val missingFiles: List<String> = emptyList(),
    val mismatchedFiles: List<String> = emptyList(),
    val versionMarker: String? = null,
    val archMarker: String? = null,
    val versionMatches: Boolean = false,
    val archMatches: Boolean = false,
) {
    val valid: Boolean
        get() = missingFiles.isEmpty() && mismatchedFiles.isEmpty() &&
            versionMatches && archMatches
}

/**
 * Pure, context-independent file helpers shared by the real bootstrap and the
 * deterministic self-check: SHA-256 digests, path-traversal rejection, and
 * installed-tree validation. No Android APIs are used here, so the checks run
 * against any real directory (assets staging, an installed copy, or a crafted
 * scratch tree in the self-check).
 */
object BaseUserspaceFiles {

    /** Lowercase-hex SHA-256 of [data]. */
    fun digestHex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /** Lowercase-hex SHA-256 of the full contents of [stream]. */
    fun digestHex(stream: InputStream): String = digestHex(stream.readBytes())

    /** Lowercase-hex SHA-256 of [file]'s bytes (empty string if the file cannot be read). */
    fun sha256Of(file: File): String? = runCatching { file.inputStream().use(::digestHex) }.getOrNull()

    /**
     * True only when [relative] is a safe, plain artifact-relative path that
     * stays inside the intended userspace directory. Rejects empty paths,
     * absolute paths, backslashes, ".", "..", empty segments, and any traversal
     * (a single ".." segment is enough to reject). This is the extraction
     * containment rule: every write target is derived only from allowlisted
     * manifest names that pass this check.
     */
    fun isSafeRelativePath(relative: String): Boolean {
        if (relative.isEmpty() || relative.startsWith('/') || relative.contains('\\')) return false
        if (relative == "." || relative == "..") return false
        for (segment in relative.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == "..") return false
        }
        return true
    }

    /**
     * Validates [root] as an installed base-userspace tree: every [manifest] path
     * exists under [root] with the expected digest, and the VERSION/ARCH markers
     * equal [expectedVersion]/[expectedArch]. Reads only files whose relative
     * paths are safe. Never mutates [root].
     */
    fun validateInstalledTree(
        root: File,
        manifest: Map<String, String>,
        expectedVersion: String,
        expectedArch: String,
        versionFile: String = BaseUserspaceArtifact.VERSION_FILE,
        archFile: String = BaseUserspaceArtifact.ARCH_FILE,
    ): BaseUserspaceTreeValidation {
        val missing = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        for ((relative, expected) in manifest) {
            if (!isSafeRelativePath(relative)) {
                missing += relative
                continue
            }
            val file = File(root, relative)
            if (!file.isFile) {
                missing += relative
                continue
            }
            val actual = sha256Of(file)
            if (actual != expected) {
                mismatched += relative
            }
        }
        return BaseUserspaceTreeValidation(
            missingFiles = missing,
            mismatchedFiles = mismatched,
            versionMarker = readTextFile(File(root, versionFile)),
            archMarker = readTextFile(File(root, archFile)),
            versionMatches = readTextFile(File(root, versionFile)) == expectedVersion,
            archMatches = readTextFile(File(root, archFile)) == expectedArch,
        )
    }

    /** Reads [file]'s bytes into a byte array, or null when the file is absent. */
    fun readBytes(file: File): ByteArray? =
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null

    /** Reads [file] as trimmed UTF-8 text, or null when absent/unreadable. */
    fun readTextFile(file: File): String? =
        readBytes(file)?.toString(Charsets.UTF_8)?.trim()
}
