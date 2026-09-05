package app.aliasnull.shell.bootstrap

/**
 * The package-format foundation selected by the Part 27-U-AUDIT architecture
 * decision and implemented by Part 27-U-IMPLEMENTATION.
 *
 * A future optional AliasNull userspace package is represented canonically as a
 * directory/tree whose payload files are described by ONE [PackageManifest] file
 * living in that tree. Per-file SHA-256 is the payload integrity mechanism, and
 * package state will live OUTSIDE the verified base artifact
 * (`userspace/packages/<name>/`, `metadata/packages/`, `tmp/staging-packages/`,
 * `tmp/backup-packages/` per [PackageLayout]).
 *
 * THIS FILE IS METADATA-ONLY. It introduces no runtime behavior: it cannot
 * install, remove, update, execute, create directories, write state, touch the
 * network, parse archives, verify signatures, or reach the native process seam.
 * It exists so a future package transaction/verification milestone has a strict,
 * deterministic, self-checked manifest model to build on - and so no later
 * milestone invents an inconsistent package format or layout.
 *
 * The model deliberately keeps four kinds of data structurally distinct:
 *
 *   A. package identity/metadata  - formatVersion, name, version, arch;
 *   B. payload integrity          - [PackageFileEntry] list (path + sha256 [+exec]);
 *   C. provenance/build metadata  - [PackageManifest.provenance] (informational,
 *                                   never trust-bearing, excluded from the future
 *                                   signature coverage);
 *   D. future authenticity        - [PackageManifest.signature] (a reserved,
 *                                   structurally validated slot; never verified
 *                                   or generated here).
 *
 * The canonical representation is a strict, deterministic, line-based UTF-8 text
 * (see [PackageManifest.canonicalText]) intended to be stable enough for a future
 * manifest SHA-256 and a future signature over
 * [PackageManifest.signedCoverageText]. Parsing is strict: malformed, duplicate,
 * ambiguous or unknown input is rejected, never silently repaired or trimmed.
 */

/** One payload file entry: a safe relative [path], its expected [sha256], and an
 * optional executable flag. [exec]=true is canonical for an executable payload
 * entry; at most one entry per manifest may declare it (metadata only here - no
 * chmod, no ELF validation, no execution in this milestone). */
internal data class PackageFileEntry(
    val path: String,
    val sha256: String,
    val exec: Boolean = false,
)

/** An exact package reference: [name] plus an optional exact [version]
 * (`package-name` or `package-name=version`). Version ranges, resolvers and
 * repository lookups are deliberately out of scope. */
internal data class PackageReference(
    val name: String,
    val version: String? = null,
) {
    /** The canonical single-token spelling: `name` or `name=version`. */
    val canonicalText: String
        get() = if (version == null) name else "$name=$version"
}

/** The reserved future authenticity slot: a [scheme] token plus the signature
 * [hex]. Structurally validated here only; never generated, verified or trusted
 * in this milestone. */
internal data class PackageSignature(
    val scheme: String,
    val hex: String,
)

/** The versioned package manifest. Canonical, deterministic, strictly parsed.
 * Produced only through [PackageManifest.create] or [PackageManifest.parse], both
 * of which validate every rule and store file/reference lists in canonical order,
 * so two equivalent manifests always compare equal and encode to identical bytes. */
internal data class PackageManifest(
    val formatVersion: Int,
    val name: String,
    val version: String,
    val arch: String,
    val files: List<PackageFileEntry>,
    val depends: List<PackageReference> = emptyList(),
    val conflicts: List<PackageReference> = emptyList(),
    val replaces: List<PackageReference> = emptyList(),
    val provenance: Map<String, String> = emptyMap(),
    val signature: PackageSignature? = null,
) {

    /** The deterministic canonical encoding: one record per LF-terminated line,
     * explicit field ordering, file entries ordered by ascending path, reference
     * lists ordered by ascending canonical spelling, provenance keys ascending.
     * Independent of locale, platform newline and hash order. */
    val canonicalText: String
        get() = encodeText(includeProvenance = true, includeSignature = true)

    /** The canonical encoding as UTF-8 bytes (the future manifest SHA-256 input). */
    val canonicalBytes: ByteArray
        get() = canonicalText.toByteArray(Charsets.UTF_8)

    /**
     * The canonical bytes a future signature is defined to cover: everything the
     * canonical form carries EXCEPT the informational [provenance] group and the
     * signature block itself (so a signature never signs its own field and never
     * depends on build bookkeeping). A signature therefore covers identity,
     * dependency/conflict/replace references and the payload file-digest list
     * only, and never provenance.
     */
    val signedCoverageText: String
        get() = encodeText(includeProvenance = false, includeSignature = false)

    private fun encodeText(includeProvenance: Boolean, includeSignature: Boolean): String = buildString {
        append("formatVersion=").append(formatVersion).append('\n')
        append("name=").append(name).append('\n')
        append("version=").append(version).append('\n')
        append("arch=").append(arch).append('\n')
        for (entry in files.sortedBy { it.path }) {
            append("file ").append(entry.path).append(' ').append(entry.sha256)
            if (entry.exec) append(" exec")
            append('\n')
        }
        appendReferences(this, "depends", depends)
        appendReferences(this, "conflicts", conflicts)
        appendReferences(this, "replaces", replaces)
        if (includeProvenance) {
            for (key in provenance.keys.sorted()) {
                append("provenance.").append(key).append('=').append(provenance.getValue(key)).append('\n')
            }
        }
        if (includeSignature && signature != null) {
            append("signature.scheme=").append(signature.scheme).append('\n')
            append("signature.value=").append(signature.hex).append('\n')
        }
    }

    private fun appendReferences(sb: StringBuilder, label: String, refs: List<PackageReference>) {
        for (ref in refs.sortedBy { it.canonicalText }) {
            sb.append(label).append('=').append(ref.canonicalText).append('\n')
        }
    }

    companion object {

        /** The manifest schema version. Independent of any package version and of
         * the base-userspace artifact VERSION (which is [BaseUserspaceArtifact.VERSION],
         * currently 4); describes the manifest format, not software. */
        const val FORMAT_VERSION = 1

        /** The first and only supported package architecture (ABI filter). Mirrors
         * [BaseUserspaceArtifact.ARCH]; other ABIs are not handled in this milestone. */
        const val SUPPORTED_ARCH = BaseUserspaceArtifact.ARCH

        /** Package names must stay short enough to be a future directory name. */
        private const val NAME_MAX_LENGTH = 64
        private const val VERSION_MAX_LENGTH = 64
        private const val PATH_MAX_LENGTH = 512
        private const val PROVENANCE_KEY_MAX_LENGTH = 64
        private const val PROVENANCE_VALUE_MAX_LENGTH = 256
        private const val SIGNATURE_SCHEME_MAX_LENGTH = 32

        private val PACKAGE_NAME = Regex("^[a-z0-9][a-z0-9._-]*$")
        private val PACKAGE_VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val PROVENANCE_KEY = Regex("^[A-Za-z][A-Za-z0-9]*$")
        private val SIGNATURE_SCHEME = Regex("^[a-z][a-z0-9-]*$")
        private val SIGNATURE_HEX = Regex("^[0-9a-f]+$")

        // ---- Individual rules (pure; used by create/parse and the self-check). ----

        /** True only when [value] is exactly the supported manifest schema version. */
        fun isValidFormatVersion(value: Int): Boolean = value == FORMAT_VERSION

        /**
         * True only when [name] is a safe future directory name
         * (`userspace/packages/<name>/`): 1..64 characters of lowercase letters,
         * digits, and inner `_`, `-`, `.`; the first character must be a lowercase
         * letter or digit (so `.`, `..`, `/`, `\`, whitespace, control characters
         * and uppercase are all impossible). An invalid name is rejected, never
         * silently normalized.
         */
        fun isValidPackageName(name: String): Boolean =
            name.isNotEmpty() && name.length <= NAME_MAX_LENGTH && PACKAGE_NAME.matches(name)

        /**
         * True only when [version] is a plain deterministic package-version string
         * in the repository's simple version style: 1..64 chars starting with a
         * letter/digit and continuing with letters/digits/`_`/`.`/`+`/`-`. It is
         * deliberately not semver-parsed and never confused with [FORMAT_VERSION].
         */
        fun isValidPackageVersion(version: String): Boolean =
            version.isNotEmpty() && version.length <= VERSION_MAX_LENGTH &&
                PACKAGE_VERSION.matches(version)

        /** True only when [arch] is the single supported ABI, [SUPPORTED_ARCH]. */
        fun isValidArch(arch: String): Boolean = arch == SUPPORTED_ARCH

        /**
         * True only when [path] is a safe package payload path: it must satisfy the
         * existing base-userspace containment rule
         * ([BaseUserspaceFiles.isSafeRelativePath]) AND consist only of printable
         * non-space ASCII (0x21..0x7E), so it can never carry a space, control
         * character, newline or separator that would make the line-based canonical
         * record ambiguous. Nested paths (`a/b/c`) are allowed. This is a small,
         * explicit package-format wrapper - it never weakens the base rule.
         */
        fun isValidPackagePath(path: String): Boolean =
            path.length <= PATH_MAX_LENGTH &&
                BaseUserspaceFiles.isSafeRelativePath(path) &&
                path.all { it in '\u0021'..'\u007e' }

        /** True only when [sha256] is exactly 64 lowercase hexadecimal characters. */
        fun isValidSha256Hex(sha256: String): Boolean = SHA256_HEX.matches(sha256)

        /** True only when [key] is a valid provenance field name. */
        fun isValidProvenanceKey(key: String): Boolean =
            key.length <= PROVENANCE_KEY_MAX_LENGTH && PROVENANCE_KEY.matches(key)

        /** True only when [value] is a valid provenance value: printable ASCII
         * except `=` (so a canonical line can never be ambiguous). */
        fun isValidProvenanceValue(value: String): Boolean =
            value.isNotEmpty() && value.length <= PROVENANCE_VALUE_MAX_LENGTH &&
                value.all { it in '\u0020'..'\u007e' && it != '=' }

        /** True only when [scheme] is a valid signature-scheme token. */
        fun isValidSignatureScheme(scheme: String): Boolean =
            scheme.length <= SIGNATURE_SCHEME_MAX_LENGTH && SIGNATURE_SCHEME.matches(scheme)

        /** True only when [hex] is a non-empty even-length lowercase hex string
         * (a future signature value; its length is scheme-dependent and left open). */
        fun isValidSignatureHex(hex: String): Boolean =
            hex.length % 2 == 0 && SIGNATURE_HEX.matches(hex)

        // ---- Reference handling ----

        /**
         * Parses the canonical reference spelling `name` or `name=version` into a
         * [PackageReference], or returns null when the spelling is malformed or a
         * component violates its own rule. Used by the parser; never resolves
         * anything.
         */
        fun parseReference(text: String): PackageReference? {
            if (text.isEmpty()) return null
            val separator = text.indexOf('=')
            return if (separator < 0) {
                if (isValidPackageName(text)) PackageReference(text) else null
            } else {
                val name = text.substring(0, separator)
                val version = text.substring(separator + 1)
                if (version.indexOf('=') >= 0) return null
                if (!isValidPackageName(name) || !isValidPackageVersion(version)) return null
                PackageReference(name, version)
            }
        }

        // ---- Construction ----

        /**
         * Validates every input rule and returns a canonical [PackageManifest].
         * Throws [PackageManifestException] describing the FIRST rule violated;
         * never normalizes invalid input.
         */
        fun create(
            formatVersion: Int = FORMAT_VERSION,
            name: String,
            version: String,
            arch: String = SUPPORTED_ARCH,
            files: List<PackageFileEntry>,
            depends: List<PackageReference> = emptyList(),
            conflicts: List<PackageReference> = emptyList(),
            replaces: List<PackageReference> = emptyList(),
            provenance: Map<String, String> = emptyMap(),
            signature: PackageSignature? = null,
        ): PackageManifest {
            requireFormatVersion(formatVersion)
            requirePackageName(name)
            requirePackageVersion(version)
            requireArch(arch)
            if (files.isEmpty()) {
                throw PackageManifestException("a package manifest must describe at least one payload file")
            }
            val paths = HashSet<String>()
            var executableCount = 0
            for (entry in files) {
                requirePackagePath(entry.path)
                requireSha256(entry.sha256)
                if (!paths.add(entry.path)) {
                    throw PackageManifestException(
                        "duplicate package file path '${entry.path}' (a path may appear at most once)",
                    )
                }
                if (entry.exec) executableCount++
            }
            if (executableCount > 1) {
                throw PackageManifestException(
                    "at most one executable payload entry is allowed (found $executableCount)",
                )
            }
            requireReferences("depends", depends)
            requireReferences("conflicts", conflicts)
            requireReferences("replaces", replaces)
            for ((key, value) in provenance) {
                if (!isValidProvenanceKey(key)) {
                    throw PackageManifestException("invalid provenance field name '$key'")
                }
                if (!isValidProvenanceValue(value)) {
                    throw PackageManifestException("invalid provenance value for field '$key'")
                }
            }
            if (signature != null) {
                requireSignature(signature)
            }
            return PackageManifest(
                formatVersion = formatVersion,
                name = name,
                version = version,
                arch = arch,
                files = files.sortedBy { it.path },
                depends = canonicalizeReferences(depends),
                conflicts = canonicalizeReferences(conflicts),
                replaces = canonicalizeReferences(replaces),
                provenance = provenance,
                signature = signature,
            )
        }

        // ---- Parsing ----

        /**
         * Strictly parses the canonical representation in one pass. Rejects
         * malformed syntax, unknown fields, duplicate scalar/provenance/signature
         * fields, duplicate file paths, malformed SHA-256, unsafe paths, invalid
         * names/versions/architectures, unsupported format versions, malformed
         * dependency/conflict/replace references, invalid boolean spellings and a
         * partial or malformed signature slot. Returns a canonical model; never
         * repairs or trims input.
         */
        fun parse(text: String): PackageManifest {
            var formatVersion: Int? = null
            var name: String? = null
            var version: String? = null
            var arch: String? = null
            val files = mutableListOf<PackageFileEntry>()
            val depends = mutableListOf<PackageReference>()
            val conflicts = mutableListOf<PackageReference>()
            val replaces = mutableListOf<PackageReference>()
            val provenance = LinkedHashMap<String, String>()
            var signatureScheme: String? = null
            var signatureHex: String? = null
            val filePaths = HashSet<String>()
            var executableCount = 0

            for (line in linesOf(text)) {
                when {
                    line.startsWith("file ") -> {
                        val entry = parseFileLine(line, filePaths, executableCount)
                        executableCount = entry.second
                        files.add(entry.first)
                    }
                    line.startsWith("provenance.") -> {
                        val body = line.removePrefix("provenance.")
                        val separator = body.indexOf('=')
                        if (separator <= 0) {
                            throw PackageManifestException("malformed provenance record '$line'")
                        }
                        val key = body.substring(0, separator)
                        val value = body.substring(separator + 1)
                        if (!isValidProvenanceKey(key)) {
                            throw PackageManifestException("invalid provenance field name '$key'")
                        }
                        if (!isValidProvenanceValue(value)) {
                            throw PackageManifestException("invalid provenance value for field '$key'")
                        }
                        if (provenance.put(key, value) != null) {
                            throw PackageManifestException("duplicate provenance field '$key'")
                        }
                    }
                    line.startsWith("signature.scheme=") -> {
                        if (signatureScheme != null) {
                            throw PackageManifestException("duplicate signature.scheme record")
                        }
                        val scheme = line.removePrefix("signature.scheme=")
                        requireSignatureScheme(scheme)
                        signatureScheme = scheme
                    }
                    line.startsWith("signature.value=") -> {
                        if (signatureHex != null) {
                            throw PackageManifestException("duplicate signature.value record")
                        }
                        val hex = line.removePrefix("signature.value=")
                        requireSignatureHex(hex)
                        signatureHex = hex
                    }
                    else -> {
                        val (key, value) = splitField(line)
                        when (key) {
                            "formatVersion" -> {
                                if (formatVersion != null) {
                                    throw PackageManifestException("duplicate formatVersion field")
                                }
                                val parsed = value.toIntOrNull()
                                    ?: throw PackageManifestException("formatVersion must be an integer, got '$value'")
                                if (value != parsed.toString()) {
                                    throw PackageManifestException(
                                        "formatVersion has a non-canonical spelling '$value'",
                                    )
                                }
                                requireFormatVersion(parsed)
                                formatVersion = parsed
                            }
                            "name" -> {
                                if (name != null) {
                                    throw PackageManifestException("duplicate name field")
                                }
                                requirePackageName(value)
                                name = value
                            }
                            "version" -> {
                                if (version != null) {
                                    throw PackageManifestException("duplicate version field")
                                }
                                requirePackageVersion(value)
                                version = value
                            }
                            "arch" -> {
                                if (arch != null) {
                                    throw PackageManifestException("duplicate arch field")
                                }
                                requireArch(value)
                                arch = value
                            }
                            "depends" -> appendUniqueReference(depends, key, value)
                            "conflicts" -> appendUniqueReference(conflicts, key, value)
                            "replaces" -> appendUniqueReference(replaces, key, value)
                            else -> throw PackageManifestException("unrecognized manifest record '$line'")
                        }
                    }
                }
            }

            val resolvedFormat =
                formatVersion ?: throw PackageManifestException("missing mandatory formatVersion field")
            val resolvedName = name ?: throw PackageManifestException("missing mandatory name field")
            val resolvedVersion = version ?: throw PackageManifestException("missing mandatory version field")
            val resolvedArch = arch ?: throw PackageManifestException("missing mandatory arch field")
            if (files.isEmpty()) {
                throw PackageManifestException("a package manifest must describe at least one payload file")
            }
            val hasScheme = signatureScheme != null
            val hasValue = signatureHex != null
            if (hasScheme != hasValue) {
                throw PackageManifestException(
                    "a signature slot requires both signature.scheme and signature.value",
                )
            }
            val signature =
                if (hasScheme) PackageSignature(signatureScheme!!, signatureHex!!) else null

            return create(
                formatVersion = resolvedFormat,
                name = resolvedName,
                version = resolvedVersion,
                arch = resolvedArch,
                files = files,
                depends = depends,
                conflicts = conflicts,
                replaces = replaces,
                provenance = provenance,
                signature = signature,
            )
        }

        private fun splitField(line: String): Pair<String, String> {
            val separator = line.indexOf('=')
            if (separator <= 0) {
                throw PackageManifestException("unrecognized or malformed manifest record '$line'")
            }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            if (value.isEmpty()) {
                throw PackageManifestException("manifest field '$key' has an empty value")
            }
            return key to value
        }

        private fun appendUniqueReference(
            target: MutableList<PackageReference>,
            label: String,
            value: String,
        ) {
            val reference = parseReference(value)
                ?: throw PackageManifestException("malformed $label reference '$value'")
            if (target.any { it == reference }) {
                throw PackageManifestException("duplicate $label reference '${reference.canonicalText}'")
            }
            target.add(reference)
        }

        private fun parseFileLine(
            line: String,
            paths: HashSet<String>,
            executableCount: Int,
        ): Pair<PackageFileEntry, Int> {
            val body = line.removePrefix("file ")
            val parts = body.split(' ')
            if (parts.size != 2 && parts.size != 3) {
                throw PackageManifestException("malformed file record '$line'")
            }
            val path = parts[0]
            val sha256 = parts[1]
            var exec = false
            if (parts.size == 3) {
                if (parts[2] != "exec") {
                    throw PackageManifestException(
                        "invalid file record boolean '$line' (only the exact token 'exec' is valid)",
                    )
                }
                exec = true
            }
            if (!isValidPackagePath(path)) {
                throw PackageManifestException("unsafe or invalid package file path '$path'")
            }
            if (!isValidSha256Hex(sha256)) {
                throw PackageManifestException(
                    "malformed SHA-256 '$sha256' for '$path' (must be 64 lowercase hex)",
                )
            }
            if (!paths.add(path)) {
                throw PackageManifestException("duplicate package file path '$path'")
            }
            val updatedCount = if (exec) executableCount + 1 else executableCount
            if (updatedCount > 1) {
                throw PackageManifestException("more than one executable payload entry is declared")
            }
            return PackageFileEntry(path, sha256, exec) to updatedCount
        }

        private fun requireFormatVersion(value: Int) {
            if (!isValidFormatVersion(value)) {
                throw PackageManifestException(
                    "unsupported manifest format version $value (supported: $FORMAT_VERSION)",
                )
            }
        }

        private fun requirePackageName(name: String) {
            if (!isValidPackageName(name)) {
                throw PackageManifestException("invalid package name '$name'")
            }
        }

        private fun requirePackageVersion(version: String) {
            if (!isValidPackageVersion(version)) {
                throw PackageManifestException("invalid package version '$version'")
            }
        }

        private fun requireArch(arch: String) {
            if (!isValidArch(arch)) {
                throw PackageManifestException(
                    "unsupported package architecture '$arch' (supported: $SUPPORTED_ARCH)",
                )
            }
        }

        private fun requirePackagePath(path: String) {
            if (!isValidPackagePath(path)) {
                throw PackageManifestException("unsafe or invalid package file path '$path'")
            }
        }

        private fun requireSha256(sha256: String) {
            if (!isValidSha256Hex(sha256)) {
                throw PackageManifestException("malformed SHA-256 '$sha256' (must be 64 lowercase hex)")
            }
        }

        private fun requireReferences(label: String, refs: List<PackageReference>) {
            val seen = HashSet<String>()
            for (ref in refs) {
                if (!isValidPackageName(ref.name)) {
                    throw PackageManifestException("invalid $label package name '${ref.name}'")
                }
                if (ref.version != null && !isValidPackageVersion(ref.version)) {
                    throw PackageManifestException("invalid $label package version '${ref.version}'")
                }
                if (!seen.add(ref.canonicalText)) {
                    throw PackageManifestException("duplicate $label reference '${ref.canonicalText}'")
                }
            }
        }

        private fun canonicalizeReferences(refs: List<PackageReference>): List<PackageReference> =
            refs.sortedBy { it.canonicalText }

        private fun requireSignatureScheme(scheme: String) {
            if (!isValidSignatureScheme(scheme)) {
                throw PackageManifestException("invalid signature scheme '$scheme'")
            }
        }

        private fun requireSignatureHex(hex: String) {
            if (!isValidSignatureHex(hex)) {
                throw PackageManifestException("invalid signature value '$hex' (non-empty even-length lowercase hex)")
            }
        }

        private fun requireSignature(signature: PackageSignature) {
            requireSignatureScheme(signature.scheme)
            requireSignatureHex(signature.hex)
        }

        /** Splits canonical text into records, rejecting '\r' and blank lines. The
         * single trailing newline of the canonical form yields no empty record. */
        private fun linesOf(text: String): List<String> {
            if (text.isEmpty()) {
                throw PackageManifestException("manifest text is empty")
            }
            if (text.contains('\r')) {
                throw PackageManifestException("manifest text must use LF line endings (found CR)")
            }
            val lines = text.split('\n')
            val effective = if (lines.isNotEmpty() && lines.last().isEmpty()) {
                lines.dropLast(1)
            } else {
                lines
            }
            if (effective.any { it.isEmpty() }) {
                throw PackageManifestException("manifest text contains a blank line")
            }
            return effective
        }
    }
}

/**
 * Thrown by [PackageManifest.create] and [PackageManifest.parse] for any rule
 * violation. A rejected manifest is never partially accepted.
 */
internal class PackageManifestException(message: String) : IllegalArgumentException(message)

/**
 * The future package storage layout selected by the Part 27-U-AUDIT, expressed as
 * deterministic path definitions ONLY. Nothing here creates, writes, mkdirs,
 * validates, or migrates anything at runtime, and nothing is wired into the
 * bootstrap, Shell or startup. The constants exist so the future package
 * transaction/verification milestone composes paths consistently under the same
 * app-private runtime root that [BaseUserspaceBootstrap] already owns
 * (`<filesDir>/aliasnull_base_userspace/`).
 */
internal object PackageLayout {

    /** Top-level runtime root sub-directory names (matching BaseUserspaceBootstrap's layout). */
    const val USERSPACE_DIR = "userspace"
    const val METADATA_DIR = "metadata"
    const val TMP_DIR = "tmp"

    /** The installed base-artifact directory name under [USERSPACE_DIR]. */
    const val BASE_DIR = "base"

    /** The future optional-package directory name under [USERSPACE_DIR] and [METADATA_DIR]. */
    const val PACKAGES_DIR = "packages"

    /** Future staging/backup directory names under [TMP_DIR]. */
    const val STAGING_PACKAGES_DIR = "staging-packages"
    const val BACKUP_PACKAGES_DIR = "backup-packages"

    /** `userspace/packages` - future installed optional packages. */
    const val PACKAGES_RELATIVE_DIR = "$USERSPACE_DIR/$PACKAGES_DIR"

    /** `metadata/packages` - future per-package state records and derived index. */
    const val PACKAGE_METADATA_RELATIVE_DIR = "$METADATA_DIR/$PACKAGES_DIR"

    /** `tmp/staging-packages` - future package staging before atomic promotion. */
    const val STAGING_PACKAGES_RELATIVE_DIR = "$TMP_DIR/$STAGING_PACKAGES_DIR"

    /** `tmp/backup-packages` - future pre-replacement/removal backup/rollback. */
    const val BACKUP_PACKAGES_RELATIVE_DIR = "$TMP_DIR/$BACKUP_PACKAGES_DIR"

    /** The future install directory of package [name] relative to the runtime
     * root (`userspace/packages/<name>`), or null when [name] is not a valid
     * package name. Pure path composition - nothing is created or inspected. */
    fun packageDirectory(name: String): String? =
        if (PackageManifest.isValidPackageName(name)) "$PACKAGES_RELATIVE_DIR/$name" else null
}
