package app.aliasnull.shell.runtime

import app.aliasnull.shell.bootstrap.BaseUserspaceFiles
import java.io.File

/**
 * TEMPORARY developer diagnostic for Part 27-S2-PERM-FIX (Phase 2): the Base
 * Executable self-check still fails at the child `execve()` with EACCES even
 * after the base userspace moved under filesDir, so the real on-device cause
 * must be observed, not assumed. The shared execve-EACCES signature (parent
 * `access(X_OK)` passes, child `execve` fails) is produced by two different
 * causes that only on-device facts can tell apart: a noexec mount on the file's
 * filesystem, or SELinux denying execution on the file's type (execute_no_trans)
 * because the file is not actually labeled app_data_file.
 *
 * [capture] reads ground truth about the exact installed executable that the
 * runner resolved and execve()'d: its absolute path, its real lstat mode bits,
 * the app process's SELinux context, the file's security.selinux label (real
 * `getxattr` syscall), and the containing mount's type/options. The lines are
 * shown in the failing Base Executable result and logged; this object and its
 * wiring are removed once the true cause is fixed.
 */
internal object BaseExecutableFailureDiagnostics {

    fun capture(executable: File): List<String> {
        val lines = mutableListOf<String>()
        val path = runCatching { executable.canonicalPath }.getOrDefault(executable.path)
        lines += "installed executable: $path"
        val mode = BaseUserspaceFiles.modeBits(executable)
        lines += "lstat mode: " + (mode?.let { describeMode(it) } ?: "cannot stat")
        lines += "process context: " + readTextOr("/proc/self/attr/current", "unreadable")
        lines += "file context: " + readSelinuxContext(executable)
        lines += "mount: " + containingMount(path)
        return lines
    }

    /** Renders the file type plus the nine permission bits and per-class exec flags. */
    private fun describeMode(mode: Int): String {
        val type = when (mode and S_IFMT) {
            S_IFREG -> "regular file"
            S_IFDIR -> "directory"
            S_IFLNK -> "symbolic link"
            else -> "unknown type"
        }
        val perms = mode and S_PERM_BITS
        return "$type mode=0${Integer.toOctalString(perms)} " +
            "ownerExec=${(perms and S_IXUSR) != 0} " +
            "groupExec=${(perms and S_IXGRP) != 0} otherExec=${(perms and S_IXOTH) != 0}"
    }

    private fun readSelinuxContext(file: File): String =
        runCatching {
            // security.selinux is a NUL-terminated UTF-8 context string.
            android.system.Os.getxattr(file.path, SELINUX_XATTR)
                .toString(Charsets.UTF_8)
                .trimEnd('\u0000')
        }.fold({ it }, { "unreadable (${it.message ?: it::class.simpleName})" })

    /**
     * Finds the mountinfo row whose mount point is the longest prefix of [path]
     * (falling back to the root mount) and reports its point, filesystem type,
     * options and whether the options include noexec.
     */
    private fun containingMount(path: String): String {
        val mountInfo =
            runCatching { File("/proc/self/mountinfo").readLines() }.getOrDefault(emptyList())
        var bestPoint = ""
        var bestFields: List<String>? = null
        for (raw in mountInfo) {
            val fields = raw.split(' ')
            if (fields.size < 10) continue
            val point = fields[4]
            if (point != "/" && (path == point || path.startsWith(point)) &&
                point.length > bestPoint.length
            ) {
                bestPoint = point
                bestFields = fields
            }
        }
        if (bestFields == null) {
            for (raw in mountInfo) {
                val fields = raw.split(' ')
                if (fields.size >= 10 && fields[4] == "/") {
                    bestPoint = "/"
                    bestFields = fields
                    break
                }
            }
        }
        val fields = bestFields ?: return "no mountinfo match"
        val options = if (fields.size > 5) fields[5] else "?"
        val dash = fields.indexOf("-")
        val type = if (dash >= 0 && fields.size > dash + 1) fields[dash + 1] else "?"
        val noexec = options.split(',').any { it == "noexec" }
        return "mountpoint=$bestPoint fstype=$type options=$options" +
            if (noexec) " [NOEXEC PRESENT]" else ""
    }

    private fun readTextOr(path: String, fallback: String): String =
        runCatching { File(path).readText().trim() }.getOrDefault(fallback)

    private const val SELINUX_XATTR = "security.selinux"
    private const val S_IFMT = 0xF000
    private const val S_IFREG = 0x8000
    private const val S_IFDIR = 0x4000
    private const val S_IFLNK = 0xA000
    private const val S_PERM_BITS = 0x1FF
    private const val S_IXUSR = 0x40
    private const val S_IXGRP = 0x08
    private const val S_IXOTH = 0x01
}
