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
 * File helpers shared by the real bootstrap and the deterministic self-check:
 * SHA-256 digests, path-traversal rejection, and installed-tree validation.
 *
 * The digest/path/tree helpers are pure and run against any real directory
 * (assets staging, an installed copy, or a crafted scratch tree in the
 * self-check). The executable mode helpers use the Android `android.system.Os`
 * wrapper over the real Linux `chmod`/`lstat` syscalls, because java.io.File's
 * permission/`canExecute` APIs are not a reliable signal of whether the platform
 * will actually let the process `execve()` the file (Part 27-S2-PERM-FIX): on a
 * real device `File.canExecute()` can report true while `execve()` is still
 * denied under SELinux. The self-check runs inside the app, so `android.system.Os`
 * is available there too.
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
     * Applies a narrow, deliberate executable mode to [file] after asset
     * extraction (APK assets do not reliably preserve Unix mode bits): the owner
     * may read/write/execute (0700) and no group/other bit is granted, so the
     * file is never writable by or executable for an untrusted user. The mode is
     * applied with the real Linux `chmod` via [android.system.Os] (not the
     * java.io.File permission API, which is not a trustworthy signal of real
     * execve-ability). Returns true only when the resulting file is a regular
     * file with the owner-execute bit set. chmod is not a substitute for
     * integrity: callers still digest- and format-verify the file.
     */
    fun applyExecutableOwnerMode(file: File): Boolean = runCatching {
        android.system.Os.chmod(file.path, MODE_OWNER_RWX)
        val mode = android.system.Os.stat(file.path).st_mode
        (mode and S_IFMT) == S_IFREG && (mode and S_IXUSR) != 0
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
     * The POSIX st_mode of [file], or null when it cannot be stat-ed. Read with
     * the real `lstat` so a symbolic link is reported as the link itself, never
     * mistaken for its target.
     */
    fun modeBits(file: File): Int? =
        runCatching { android.system.Os.lstat(file.path).st_mode }.getOrNull()

    /** True only when [mode] is the file-type bits of a regular file. */
    fun isRegularFileMode(mode: Int): Boolean = (mode and S_IFMT) == S_IFREG

    /** True only when [mode] grants the owner execute permission. */
    fun isOwnerExecutableMode(mode: Int): Boolean = (mode and S_IXUSR) != 0

    /**
     * True only when [mode] is the exact narrow mode the bootstrap applies to the
     * bundled executable: a regular file whose owner may read/write/execute
     * (0700) and to which group and other are granted no permission bit at all.
     * This is the "narrowest safe mode" this app uses: only the AliasNull app's
     * own uid can read, write or execute the file, so it is never writable by or
     * executable for an untrusted user.
     */
    fun isNarrowOwnerOnlyExecutableMode(mode: Int): Boolean =
        (mode and S_IFMT) == S_IFREG && (mode and S_PERM_BITS) == MODE_OWNER_RWX

    /**
     * Validates [relative] under [root] as the installed bundled executable.
     * Returns null when it is a regular (non-symlink) file whose POSIX mode has
     * the owner-execute bit and whose payload is a 64-bit AArch64 ELF, otherwise
     * a short reason. Readiness is decided from the real `lstat` mode bits, not
     * from `File.canExecute()` (which on a device can report true while a real
     * `execve()` is still denied by SELinux) and never from the file merely
     * existing.
     */
    fun executableValidationError(root: File, relative: String): String? {
        if (!isSafeRelativePath(relative)) return "unsafe relative path '$relative'"
        val file = File(root, relative)
        val mode = modeBits(file) ?: return "not present or not stat-able"
        if ((mode and S_IFMT) == S_IFLNK) return "is a symbolic link, not a regular file"
        if (!isRegularFileMode(mode)) return "not a regular file"
        if (!isOwnerExecutableMode(mode)) return "missing owner-execute permission"
        if (!isElf64AArch64(file)) return "not a 64-bit AArch64 ELF"
        return null
    }

    private const val ELF_HEADER_MIN = 20
    private const val EM_AARCH64 = 0xB7

    // POSIX mode bits and file-type masks as returned by st_mode.
    private const val S_IFMT = 0xF000 // type mask
    private const val S_IFREG = 0x8000 // regular file
    private const val S_IFLNK = 0xA000 // symbolic link
    private const val S_IXUSR = 0x40 // 0100: owner execute
    private const val S_PERM_BITS = 0x1FF // 0777: the nine rwx bits (user/group/other)
    private const val MODE_OWNER_RWX = 0x1C0 // 0700: owner read/write/execute only
}
