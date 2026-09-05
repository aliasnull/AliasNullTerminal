package app.aliasnull.shell.bootstrap

import java.io.File
import java.io.FileOutputStream

/**
 * The persisted per-package install record of the Part 27-V data-only package
 * transaction (Part 27-V-IMPLEMENTATION).
 *
 * A successful first install is recorded as:
 *
 *   metadata/packages/<name>.state
 *
 * carrying exactly the four minimal deterministic key=value lines required by
 * the milestone:
 *
 *   state=INSTALLED
 *   version=<manifest version>
 *   arch=<manifest arch>
 *   manifestSha256=<SHA-256 of the canonical manifest bytes>
 *
 * It deliberately does NOT duplicate the payload file list, provenance or any
 * ownership database, and it never carries timestamps. The record claims
 * INSTALLED only when the promoted live tree has already been verified - the
 * transaction writes it only after post-promotion live verification succeeds,
 * so a package is never reported installed merely because a directory exists.
 *
 * The on-disk form reuses the tiny key=value convention of
 * [BaseUserspaceMetadata] (stable text, unknown lines ignored so the format can
 * grow). Writes are committed atomically for the small-file case: bytes are
 * written to a package-specific sibling temporary file, flushed and fsynced,
 * then atomically renamed over the final record - so a partial write is never
 * visible as `state=INSTALLED`, and no other package's metadata is ever
 * touched. The version/arch/manifestSha256 read back from a record are the
 * transaction's own honest values, never inferred from directory existence.
 */
internal data class PackageStateRecord(
    val installed: Boolean,
    val version: String?,
    val arch: String?,
    val manifestSha256: String?,
)

/** The per-package state file helper of the Part 27-V package transaction. */
internal object PackageStateFile {

    const val STATE_KEY = "state"
    const val VERSION_KEY = "version"
    const val ARCH_KEY = "arch"
    const val MANIFEST_SHA_KEY = "manifestSha256"

    /** The only state value this milestone ever writes. */
    const val INSTALLED_TOKEN = "INSTALLED"

    /** The `<name>.state` record of package [name] under [runtimeRoot]
     * (`metadata/packages/<name>.state`). Pure path composition. */
    fun stateFileFor(runtimeRoot: File, name: String): File {
        val dir = File(runtimeRoot, PackageLayout.PACKAGE_METADATA_RELATIVE_DIR)
        return File(dir, name + PackageLayout.PACKAGE_STATE_FILE_SUFFIX)
    }

    /** The deterministic record text for an INSTALLED package. */
    fun encodeInstalled(version: String, arch: String, manifestSha256: String): String = buildString {
        append(STATE_KEY).append('=').append(INSTALLED_TOKEN).append('\n')
        append(VERSION_KEY).append('=').append(version).append('\n')
        append(ARCH_KEY).append('=').append(arch).append('\n')
        append(MANIFEST_SHA_KEY).append('=').append(manifestSha256).append('\n')
    }

    /** Parses [text] into a record; absent or unknown keys keep safe defaults. */
    fun parse(text: String): PackageStateRecord {
        var state: String? = null
        var version: String? = null
        var arch: String? = null
        var manifestSha256: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            when (key) {
                STATE_KEY -> state = value
                VERSION_KEY -> version = value
                ARCH_KEY -> arch = value
                MANIFEST_SHA_KEY -> manifestSha256 = value
            }
        }
        return PackageStateRecord(
            installed = state == INSTALLED_TOKEN,
            version = version,
            arch = arch,
            manifestSha256 = manifestSha256,
        )
    }

    /** Reads the record from [file], or null when no record file exists there. */
    fun read(file: File): PackageStateRecord? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return parse(text)
    }

    /**
     * Atomically commits an INSTALLED record to [file]: writes the deterministic
     * text to a sibling temporary file, flushes and fsyncs it, then renames it
     * over the final record. Returns true only when the final file exists with
     * the committed bytes; on any failure the temporary file is removed and no
     * record change is observable.
     */
    fun writeInstalled(file: File, version: String, arch: String, manifestSha256: String): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        if (!parent.isDirectory) return false
        val temporary = File(parent, file.name + ".tmp")
        val written = runCatching {
            FileOutputStream(temporary).use { out ->
                out.write(encodeInstalled(version, arch, manifestSha256).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            temporary.renameTo(file)
        }.getOrDefault(false)
        if (!written) {
            runCatching { temporary.delete() }
            return false
        }
        return true
    }
}
