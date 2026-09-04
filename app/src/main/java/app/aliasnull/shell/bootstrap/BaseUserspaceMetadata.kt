package app.aliasnull.shell.bootstrap

import java.io.File

/**
 * The persisted base-userspace bootstrap record: one [state] plus the artifact
 * version that state refers to.
 *
 * A successful install is recorded (written as INSTALLED) only after artifact
 * extraction AND tree validation both succeed; anything less stays INSTALLING or
 * FAILED. [artifactVersion] is the [BaseUserspaceArtifact.VERSION] of the most
 * recent INSTALLED/attempted install, so the bootstrap can skip a reinstall when
 * the recorded version equals the bundled version and the tree validates, and
 * can upgrade when they differ.
 *
 * The record is stored as a tiny, stable key=value text file (not a binary blob)
 * so it is readable and debuggable on device. Unknown lines are ignored so the
 * format can grow without invalidating older records.
 */
data class BaseUserspaceMetadata(
    val state: BaseUserspaceBootstrapState,
    val artifactVersion: String?,
) {
    companion object {
        /** The record used when no metadata file exists yet. */
        val NOT_INSTALLED = BaseUserspaceMetadata(BaseUserspaceBootstrapState.NOT_INSTALLED, null)

        /** Encodes this record to its on-disk key=value text. */
        fun encode(metadata: BaseUserspaceMetadata): String = buildString {
            append("state=").append(metadata.state.name).append('\n')
            metadata.artifactVersion?.let { append("artifactVersion=").append(it).append('\n') }
        }

        /** Parses [text] into a record; unknown/absent keys keep safe defaults. */
        fun parse(text: String): BaseUserspaceMetadata {
            var state = BaseUserspaceBootstrapState.NOT_INSTALLED
            var version: String? = null
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) continue
                val separator = line.indexOf('=')
                if (separator <= 0) continue
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                when (key) {
                    "state" -> state = runCatching {
                        BaseUserspaceBootstrapState.valueOf(value)
                    }.getOrDefault(BaseUserspaceBootstrapState.NOT_INSTALLED)
                    "artifactVersion" -> version = value
                }
            }
            return BaseUserspaceMetadata(state, version)
        }

        /** Reads the record from [file], defaulting to [NOT_INSTALLED] when absent. */
        fun readFrom(file: File): BaseUserspaceMetadata {
            if (!file.isFile) return NOT_INSTALLED
            val text = runCatching { file.readText() }.getOrNull() ?: return NOT_INSTALLED
            return parse(text)
        }
    }
}
