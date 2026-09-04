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
    /**
     * A short reason the bundled executable is invalid (missing permission,
     * wrong format, a symlink instead of a regular file), or null when it is
     * valid or no executable was expected. Set only when [executableRelative]
     * was passed to [validateInstalledTree]; readiness must not depend on the
     * executable merely existing.
     */
    val executableError: String? = null,
) {
    val valid: Boolean
        get() = missingFiles.isEmpty() && mismatchedFiles.isEmpty() &&
            versionMatches && archMatches && executableError == null
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
        executableRelative: String? = null,
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
        val executableError = executableRelative?.let { executableValidationError(root, it) }
        return BaseUserspaceTreeValidation(
            missingFiles = missing,
            mismatchedFiles = mismatched,
            versionMarker = readTextFile(File(root, versionFile)),
            archMarker = readTextFile(File(root, archFile)),
            versionMatches = readTextFile(File(root, versionFile)) == expectedVersion,
            archMatches = readTextFile(File(root, archFile)) == expectedArch,
            executableError = executableError,
        )
    }

    /** Reads [file]'s bytes into a byte array, or null when the file is absent. */
    fun readBytes(file: File): ByteArray? =
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null

    /** Reads [file] as trimmed UTF-8 text, or null when absent/unreadable. */
    fun readTextFile(file: File): String? =
        readBytes(file)?.toString(Charsets.UTF_8)?.trim()

    // ---- Executable support (Part 27-S2) ----

    /**
     * Restores a narrow, deliberate executable permission on [file] after asset
     * extraction (APK assets do not reliably preserve Unix mode bits): owner may
     * read/write/execute, group/other are never made writable. Returns true only
     * when the resulting file is executable by the owner. chmod is not a
     * substitute for integrity: callers still digest- and format-verify the file.
     */
    fun applyExecutableOwnerMode(file: File): Boolean = runCatching {
        file.setWritable(false, false) &&
            file.setWritable(true, true) &&
            file.setReadable(true, true) &&
            file.setExecutable(true, true)
    }.getOrDefault(false)

    /** True only when [file]'s bytes are an ELF of class 64-bit little-endian. */
    fun isElf64(bytes: ByteArray): Boolean {
        if (bytes.size < ELF_HEADER_MIN) return false
        if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return false
        }
        // e_ident[EI_CLASS]=2 -> ELFCLASS64; e_ident[EI_DATA]=1 -> little-endian.
        if (bytes[4] != 2.toByte() || bytes[5] != 1.toByte()) return false
        return true
    }

    /** True only when [file] is a 64-bit ELF whose e_machine is AArch64 (0xB7). */
    fun isElf64AArch64(file: File): Boolean {
        val bytes = readBytes(file) ?: return false
        if (!isElf64(bytes)) return false
        // ELF64 header: e_machine is the 2-byte little-endian value at offset 18.
        val machine = ((bytes[19].toInt() and 0xff) shl 8) or (bytes[18].toInt() and 0xff)
        return machine == EM_AARCH64
    }

    /**
     * Validates [relative] under [root] as the installed bundled executable.
     * Returns null when it is a regular (non-symlink) file with execute
     * permission and a 64-bit AArch64 ELF payload, otherwise a short reason.
     * This is the "verify the executable itself" rule: readiness never depends on
     * the file merely existing.
     */
    fun executableValidationError(root: File, relative: String): String? {
        if (!isSafeRelativePath(relative)) return "unsafe relative path '$relative'"
        val file = File(root, relative)
        val symlink = runCatching {
            java.nio.file.Files.isSymbolicLink(file.toPath())
        }.getOrDefault(false)
        if (symlink || !file.isFile) return "not a regular file"
        if (!file.canExecute()) return "missing execute permission"
        if (!isElf64AArch64(file)) return "not a 64-bit AArch64 ELF"
        return null
    }

    private const val ELF_HEADER_MIN = 20
    private const val EM_AARCH64 = 0xB7
}
