package app.aliasnull.shell.bootstrap

/** One validation assertion in a [PackageManifestSelfCheckReport]. */
internal data class PackageManifestSelfCheckCase(
    val label: String,
    val expectedMet: Boolean,
    val detail: String,
)

/** Aggregated result of one [PackageManifestSelfCheck] run. */
internal data class PackageManifestSelfCheckReport(
    val cases: List<PackageManifestSelfCheckCase>,
) {
    val allPassed: Boolean
        get() = cases.all { it.expectedMet }

    val passedCount: Int
        get() = cases.count { it.expectedMet }
}

/**
 * Dormant, process-free and filesystem-free self-check for the package-format
 * foundation (Part 27-U). Every case calls the REAL
 * [PackageManifest.create]/[PackageManifest.parse] and the pure rule validators
 * on crafted inputs and asserts the true result. Nothing here spawns a child
 * process, touches a file, creates a directory or reaches the network; the
 * inputs are pure construction and the assertions are deterministic and depend
 * on no device state.
 *
 * The coverage is the format/storage foundation of the Part 27-U-AUDIT
 * decision: package NAME / VERSION / FORMAT / ARCH / payload FILES / the EXEC
 * flag / DEPENDENCIES / CONFLICTS / REPLACES reference lists /
 * CANONICALIZATION (the frozen deterministic encoding and the round-trip
 * property) / PROVENANCE metadata / the reserved SIGNATURE slot / the LAYOUT
 * constants. The opening case pins the canonical encoding against an
 * independently hand-written expected text for one designed sample package
 * (`demo` 1.0.0), so the canonical form is proven frozen, not merely
 * self-consistent.
 *
 * Like the other self-checks in this codebase, this object is deliberately NOT
 * wired to any Shell command, UI, startup path or package runtime; it exists so
 * the codebase (or a future test surface) can verify the manifest contract on
 * demand by calling [run].
 */
internal object PackageManifestSelfCheck {

    // Fixed, countable SHA-256 filler values for the designed sample package
    // (four 16-char groups of one hex letter each = exactly 64 lowercase hex).
    private const val HEX_16_A = "aaaaaaaaaaaaaaaa"
    private const val HEX_16_B = "bbbbbbbbbbbbbbbb"
    private const val HEX_16_C = "cccccccccccccccc"

    /** `bin/demo` (the sample's single executable payload file). */
    private val EXEC_SHA: String = HEX_16_B + HEX_16_B + HEX_16_B + HEX_16_B

    /** `data/config.txt`. */
    private val CONFIG_SHA: String = HEX_16_A + HEX_16_A + HEX_16_A + HEX_16_A

    /** `share/notes.txt`. */
    private val NOTES_SHA: String = HEX_16_C + HEX_16_C + HEX_16_C + HEX_16_C

    fun run(): PackageManifestSelfCheckReport {
        val cases = mutableListOf<PackageManifestSelfCheckCase>()

        // ---- The designed sample manifest (create() with deliberately
        // scrambled file/provenance input to prove canonicalization). ----
        val sample = PackageManifest.create(
            name = "demo",
            version = "1.0.0",
            files = listOf(
                PackageFileEntry(path = "share/notes.txt", sha256 = NOTES_SHA),
                PackageFileEntry(path = "bin/demo", sha256 = EXEC_SHA, exec = true),
                PackageFileEntry(path = "data/config.txt", sha256 = CONFIG_SHA),
            ),
            depends = listOf(PackageReference("demo-base")),
            conflicts = listOf(PackageReference("demo-old", "1.0.0")),
            provenance = linkedMapOf("ndk" to "28.2.13676358", "androidApi" to "26"),
        )

        // ---- A. Frozen canonical encoding vs an independently written text. ----

        cases += caseOf("A. the sample manifest encodes to the exact hand-written canonical text") {
            val actual = sample.canonicalText
            (actual == expectedSampleCanonicalText()) to
                (if (actual == expectedSampleCanonicalText()) {
                    "canonicalText matches the frozen literal byte-for-byte"
                } else {
                    "canonicalText:\n$actual\n\nexpected:\n${expectedSampleCanonicalText()}"
                })
        }

        cases += caseOf("A. the canonical text is exactly the model's UTF-8 canonicalBytes") {
            val bytes = sample.canonicalBytes
            (bytes.contentEquals(sample.canonicalText.toByteArray(Charsets.UTF_8))) to
                "canonicalBytes is the UTF-8 of canonicalText (${bytes.size} bytes)"
        }

        // ---- B. Header scalars: FORMAT_VERSION / NAME / VERSION / ARCH. ----

        cases += caseOf("B. FORMAT_VERSION is 1 and the sample carries it") {
            val okFormat = PackageManifest.FORMAT_VERSION == 1
            (okFormat && sample.formatVersion == 1 && sample.arch == "arm64-v8a") to
                "FORMAT_VERSION=${PackageManifest.FORMAT_VERSION}; sample format=${sample.formatVersion}, arch=${sample.arch}"
        }

        cases += caseOf("B. SUPPORTED_ARCH mirrors the base artifact ABI") {
            val ok = PackageManifest.SUPPORTED_ARCH == BaseUserspaceArtifact.ARCH &&
                BaseUserspaceArtifact.ARCH == "arm64-v8a"
            ok to "SUPPORTED_ARCH=${PackageManifest.SUPPORTED_ARCH}"
        }

        cases += caseOf("B. valid package names are accepted, invalid names rejected") {
            val valid = listOf("demo", "hello-world", "a1", "coreutils_9", "busybox-static", "x")
            val invalid = listOf("", "A", "-x", "_x", ".x", "..", "a b", "a/b", "a\\b", "a".repeat(65))
            val allOk = valid.all { PackageManifest.isValidPackageName(it) } &&
                invalid.none { PackageManifest.isValidPackageName(it) }
            allOk to "valid=${valid}; invalid rejected=${invalid.all { !PackageManifest.isValidPackageName(it) }}"
        }

        cases += caseOf("B. an invalid package name is refused by create and parse") {
            val createRejects = rejected {
                PackageManifest.create(name = "Bad Name", version = "1", files = oneFile())
            }
            val parseRejects = rejected {
                PackageManifest.parse(textOf("formatVersion=1", "name=Bad Name", "version=1", "arch=arm64-v8a"))
            }
            (createRejects.first && parseRejects.first) to
                "create: ${createRejects.second}; parse: ${parseRejects.second}"
        }

        cases += caseOf("B. valid package versions are accepted, invalid rejected") {
            val valid = listOf("1.0.0", "v1", "2024.06-rc1+b2", "V1_0", "0")
            val invalid = listOf("", "-1", ".1", "1 2", "a/b", "a\\b", "v".repeat(65))
            val allOk = valid.all { PackageManifest.isValidPackageVersion(it) } &&
                invalid.none { PackageManifest.isValidPackageVersion(it) }
            allOk to "valid=${valid}; invalid rejected=${invalid.all { !PackageManifest.isValidPackageVersion(it) }}"
        }

        cases += caseOf("B. the manifest version is never confused with the schema version") {
            val model = PackageManifest.create(name = "v", version = "9", files = oneFile())
            (model.version == "9" && model.formatVersion == PackageManifest.FORMAT_VERSION) to
                "package version 9 kept distinct from FORMAT_VERSION ${PackageManifest.FORMAT_VERSION}"
        }

        cases += caseOf("B. only the supported architecture is valid") {
            val ok = PackageManifest.isValidArch("arm64-v8a")
            val rejected = !PackageManifest.isValidArch("x86_64") &&
                !PackageManifest.isValidArch("armv7") && !PackageManifest.isValidArch("")
            (ok && rejected) to "arm64-v8a valid; x86_64/armv7/empty rejected"
        }

        // ---- C. Payload FILES and the EXEC flag. ----

        cases += caseOf("C. safe payload paths are accepted") {
            val valid = listOf(
                "bin/demo",
                "share/notes.txt",
                "a/b/c/d",
                "lib/libdemo.so",
                "data/config.yaml",
                "a",
            )
            valid.all { PackageManifest.isValidPackagePath(it) } to "valid paths accepted: $valid"
        }

        cases += caseOf("C. unsafe or ambiguous payload paths are rejected") {
            val invalid = listOf(
                "",
                "/abs",
                "bin//demo",
                "bin/../x",
                "x/./y",
                "a\\b",
                "bin/my tool",
                "bin/a\tb",
                "bin/",
                "segments/".repeat(60),
            )
            invalid.none { PackageManifest.isValidPackagePath(it) } to
                "rejected ${invalid.count { !PackageManifest.isValidPackagePath(it) }}/" +
                "${invalid.size} unsafe paths"
        }

        cases += caseOf("C. a malformed SHA-256 is rejected, a canonical one accepted") {
            val ok = PackageManifest.isValidSha256Hex(EXEC_SHA)
            val emptyRejected = !PackageManifest.isValidSha256Hex("")
            val shortRejected = !PackageManifest.isValidSha256Hex(EXEC_SHA.dropLast(1))
            val longRejected = !PackageManifest.isValidSha256Hex(EXEC_SHA + "a")
            val upperRejected = !PackageManifest.isValidSha256Hex(EXEC_SHA.uppercase())
            val nonHexRejected = !PackageManifest.isValidSha256Hex("g".repeat(64))
            val spacedRejected = !PackageManifest.isValidSha256Hex("a".repeat(32) + " " + "a".repeat(31))
            (ok && emptyRejected && shortRejected && longRejected && upperRejected &&
                nonHexRejected && spacedRejected) to
                "64 lowercase hex accepted; empty/wrong-length/uppercase/non-hex/spaced rejected"
        }

        cases += caseOf("C. the sample's executable file is exec and comes first by sorted path") {
            val sortedPaths = sample.files.map { it.path }
            val bin = sample.files.first()
            (sortedPaths == listOf("bin/demo", "data/config.txt", "share/notes.txt") &&
                bin.path == "bin/demo" && bin.exec && bin.sha256 == EXEC_SHA) to
                "sorted order = $sortedPaths; bin/demo exec=$bin"
        }

        cases += caseOf("C. create refuses more than one executable payload entry") {
            rejected {
                PackageManifest.create(
                    name = "demo",
                    version = "1.0.0",
                    files = listOf(
                        PackageFileEntry("bin/one", EXEC_SHA, exec = true),
                        PackageFileEntry("bin/two", CONFIG_SHA, exec = true),
                    ),
                )
            }
        }

        cases += caseOf("C. parse refuses two executable payload entries") {
            rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1",
                    "name=demo",
                    "version=1.0.0",
                    "arch=arm64-v8a",
                    "file bin/one $EXEC_SHA exec",
                    "file bin/two $CONFIG_SHA exec",
                ))
            }
        }

        cases += caseOf("C. parse rejects a non-canonical file-record boolean token") {
            rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1",
                    "name=demo",
                    "version=1.0.0",
                    "arch=arm64-v8a",
                    "file bin/one $EXEC_SHA executable",
                ))
            }
        }

        cases += caseOf("C. parse rejects a duplicate payload path") {
            rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1",
                    "name=demo",
                    "version=1.0.0",
                    "arch=arm64-v8a",
                    "file a $EXEC_SHA",
                    "file a $CONFIG_SHA",
                ))
            }
        }

        cases += caseOf("C. parse rejects an empty payload file set") {
            rejected {
                PackageManifest.parse(textOf("formatVersion=1", "name=demo", "version=1.0.0", "arch=arm64-v8a"))
            }
        }

        // ---- D. DEPENDENCIES / CONFLICTS / REPLACES references. ----

        cases += caseOf("D. the canonical reference spellings parse to exact references") {
            val bare = PackageManifest.parseReference("demo-base")
            val exact = PackageManifest.parseReference("demo-old=1.0.0")
            val malformed = PackageManifest.parseReference("demo-old=1.0.0=2.0")
            val badName = PackageManifest.parseReference("Bad Name")
            (bare == PackageReference("demo-base") &&
                exact == PackageReference("demo-old", "1.0.0") &&
                malformed == null && badName == null) to
                "bare=$bare; exact=$exact; malformed=$malformed; badName=$badName"
        }

        cases += caseOf("D. create rejects duplicate, malformed or empty reference lists") {
            val dup = rejected {
                PackageManifest.create(
                    name = "demo", version = "1.0.0", files = oneFile(),
                    depends = listOf(PackageReference("x"), PackageReference("x")),
                )
            }
            val malformedName = rejected {
                PackageManifest.create(
                    name = "demo", version = "1.0.0", files = oneFile(),
                    conflicts = listOf(PackageReference("Bad")),
                )
            }
            val badVersion = rejected {
                PackageManifest.create(
                    name = "demo", version = "1.0.0", files = oneFile(),
                    replaces = listOf(PackageReference("x", "-bad")),
                )
            }
            (dup.first && malformedName.first && badVersion.first) to
                "dup=${dup.second}; badName=${malformedName.second}; badVersion=${badVersion.second}"
        }

        cases += caseOf("D. parse rejects a malformed reference and a duplicate reference") {
            val malformed = rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1", "name=demo", "version=1.0.0", "arch=arm64-v8a",
                    "file a $EXEC_SHA", "depends=demo-old=1.0.0=2.0",
                ))
            }
            val duplicate = rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1", "name=demo", "version=1.0.0", "arch=arm64-v8a",
                    "file a $EXEC_SHA", "conflicts=other", "conflicts=other",
                ))
            }
            (malformed.first && duplicate.first) to
                "malformed=${malformed.second}; duplicate=${duplicate.second}"
        }

        cases += caseOf("D. reference lists canonicalize to ascending canonical spelling") {
            val model = PackageManifest.create(
                name = "demo", version = "1.0.0", files = oneFile(),
                depends = listOf(PackageReference("z-lib"), PackageReference("a-lib")),
                conflicts = listOf(PackageReference("old", "9.0.0")),
            )
            (model.depends.map { it.canonicalText } == listOf("a-lib", "z-lib") &&
                model.conflicts.map { it.canonicalText } == listOf("old=9.0.0")) to
                "depends=${model.depends.map { it.canonicalText }}; conflicts=${model.conflicts.map { it.canonicalText }}"
        }

        // ---- E. Strict parsing: whole-text rejection. ----

        val header = { lines: List<String> ->
            listOf("formatVersion=1", "name=demo", "version=1.0.0", "arch=arm64-v8a", "file a $EXEC_SHA") + lines
        }

        cases += caseOf("E. parse rejects empty text and a trailing-CR block") {
            val emptyRejected = rejected { PackageManifest.parse("") }
            val crRejected = rejected { PackageManifest.parse("formatVersion=1\r\nname=demo\n") }
            (emptyRejected.first && crRejected.first) to
                "empty=${emptyRejected.second}; CR=${crRejected.second}"
        }

        cases += caseOf("E. parse rejects an unknown field") {
            rejected { PackageManifest.parse(textOf(*header(listOf("bogus=1")).toTypedArray())) }
        }

        cases += caseOf("E. parse rejects a duplicate header scalar") {
            rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1", "formatVersion=1", "name=demo", "version=1", "arch=arm64-v8a",
                    "file a $EXEC_SHA",
                ))
            }
        }

        cases += caseOf("E. parse rejects missing mandatory fields and an unsupported format") {
            val missingName = rejected {
                PackageManifest.parse(textOf("formatVersion=1", "version=1.0.0", "arch=arm64-v8a", "file a $EXEC_SHA"))
            }
            val unsupported = rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=2", "name=demo", "version=1.0.0", "arch=arm64-v8a", "file a $EXEC_SHA",
                ))
            }
            (missingName.first && unsupported.first) to
                "missingName=${missingName.second}; unsupported=${unsupported.second}"
        }

        cases += caseOf("E. parse rejects a non-canonical formatVersion spelling and unsafe paths") {
            val nonCanonical = rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=01", "name=demo", "version=1.0.0", "arch=arm64-v8a", "file a $EXEC_SHA",
                ))
            }
            val unsafePath = rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1", "name=demo", "version=1.0.0", "arch=arm64-v8a",
                    "file ../a $EXEC_SHA",
                ))
            }
            (nonCanonical.first && unsafePath.first) to
                "nonCanonical=${nonCanonical.second}; unsafePath=${unsafePath.second}"
        }

        cases += caseOf("E. parse accepts the canonical block with and without its trailing newline") {
            val withNewline = try {
                PackageManifest.parse(sample.canonicalText)
                true
            } catch (e: PackageManifestException) {
                false
            }
            val withoutNewline = try {
                PackageManifest.parse(sample.canonicalText.removeSuffix("\n"))
                true
            } catch (e: PackageManifestException) {
                false
            }
            (withNewline && withoutNewline) to "both forms parse"
        }

        cases += caseOf("E. parse of canonical text round-trips to an equal model") {
            val reparsed = PackageManifest.parse(sample.canonicalText)
            (reparsed == sample) to "parse(canonicalText) equals the original model"
        }

        cases += caseOf("E. canonical encoding is idempotent under parse") {
            val again = PackageManifest.parse(sample.canonicalText)
            (again.canonicalText == sample.canonicalText) to "parse().canonicalText == canonicalText"
        }

        cases += caseOf("E. create sorts a scrambled payload list into canonical order") {
            (sample.files.map { it.path } == listOf("bin/demo", "data/config.txt", "share/notes.txt")) to
                "files stored in canonical ascending path order"
        }

        // ---- F. PROVENANCE. ----

        cases += caseOf("F. valid provenance keys and values are accepted, invalid rejected") {
            val keyOk = PackageManifest.isValidProvenanceKey("androidApi") &&
                PackageManifest.isValidProvenanceKey("ndk")
            val keyBad = !PackageManifest.isValidProvenanceKey("1number") &&
                !PackageManifest.isValidProvenanceKey("has space") &&
                !PackageManifest.isValidProvenanceKey("")
            val valueOk = PackageManifest.isValidProvenanceValue("26") &&
                PackageManifest.isValidProvenanceValue("28.2.13676358")
            val valueBad = !PackageManifest.isValidProvenanceValue("a=b") &&
                !PackageManifest.isValidProvenanceValue("") &&
                !PackageManifest.isValidProvenanceValue("a\nb") &&
                !PackageManifest.isValidProvenanceValue("v".repeat(257))
            (keyOk && keyBad && valueOk && valueBad) to
                "keys/values validated; '=' and newline and over-length rejected"
        }

        cases += caseOf("F. canonical provenance lines are sorted by key") {
            val provenanceIndex = sample.canonicalText.indexOf("provenance.androidApi=26")
            val ndkIndex = sample.canonicalText.indexOf("provenance.ndk=28.2.13676358")
            (provenanceIndex in 0 until ndkIndex) to "androidApi sorts before ndk"
        }

        cases += caseOf("F. parse rejects a duplicate provenance field and a key-less provenance line") {
            val duplicate = rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1", "name=demo", "version=1.0.0", "arch=arm64-v8a",
                    "file a $EXEC_SHA", "provenance.androidApi=26", "provenance.androidApi=27",
                ))
            }
            val keyless = rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1", "name=demo", "version=1.0.0", "arch=arm64-v8a",
                    "file a $EXEC_SHA", "provenance.=26",
                ))
            }
            (duplicate.first && keyless.first) to "duplicate=${duplicate.second}; keyless=${keyless.second}"
        }

        // ---- G. The reserved SIGNATURE slot. ----

        cases += caseOf("G. valid signature schemes and hex values are accepted, invalid rejected") {
            val schemeOk = PackageManifest.isValidSignatureScheme("ed25519") &&
                PackageManifest.isValidSignatureScheme("a")
            val schemeBad = !PackageManifest.isValidSignatureScheme("") &&
                !PackageManifest.isValidSignatureScheme("ED25519") &&
                !PackageManifest.isValidSignatureScheme("ed 25519")
            val hexOk = PackageManifest.isValidSignatureHex("abcdef") &&
                PackageManifest.isValidSignatureHex("aabbccddeeff0011")
            val hexBad = !PackageManifest.isValidSignatureHex("") &&
                !PackageManifest.isValidSignatureHex("abc") &&
                !PackageManifest.isValidSignatureHex("ABCDEF") &&
                !PackageManifest.isValidSignatureHex("zz")
            (schemeOk && schemeBad && hexOk && hexBad) to
                "scheme/hex validated; empty, odd, uppercase and non-hex rejected"
        }

        cases += caseOf("G. a signed manifest round-trips with its signature intact") {
            val signed = PackageManifest.create(
                name = "demo", version = "1.0.0",
                files = listOf(PackageFileEntry("bin/demo", EXEC_SHA, exec = true)),
                signature = PackageSignature("ed25519", "aabbccddeeff0011"),
            )
            val reparsed = PackageManifest.parse(signed.canonicalText)
            val textHasBlock = signed.canonicalText.contains("signature.scheme=ed25519") &&
                signed.canonicalText.contains("signature.value=aabbccddeeff0011")
            (reparsed == signed && textHasBlock) to
                "signature survived the round trip; canonical block present"
        }

        cases += caseOf("G. parse rejects a partial signature slot") {
            rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1", "name=demo", "version=1.0.0", "arch=arm64-v8a",
                    "file a $EXEC_SHA", "signature.scheme=ed25519",
                ))
            }
        }

        cases += caseOf("G. parse rejects a malformed signature scheme and an invalid hex value") {
            val badScheme = rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1", "name=demo", "version=1.0.0", "arch=arm64-v8a",
                    "file a $EXEC_SHA", "signature.scheme=Ed25519", "signature.value=aabbcc",
                ))
            }
            val oddHex = rejected {
                PackageManifest.parse(textOf(
                    "formatVersion=1", "name=demo", "version=1.0.0", "arch=arm64-v8a",
                    "file a $EXEC_SHA", "signature.scheme=ed25519", "signature.value=abc",
                ))
            }
            (badScheme.first && oddHex.first) to
                "badScheme=${badScheme.second}; oddHex=${oddHex.second}"
        }

        // ---- H. Signature coverage excludes provenance and the signature block. ----

        cases += caseOf("H. signed coverage excludes provenance and the signature block") {
            val signed = PackageManifest.create(
                name = "demo", version = "1.0.0", files = oneFile(),
                provenance = mapOf("androidApi" to "26"),
                signature = PackageSignature("ed25519", "aabbccddeeff0011"),
            )
            val coverage = signed.signedCoverageText
            val excluded = !coverage.contains("provenance.") && !coverage.contains("signature.")
            val retained = coverage.contains("formatVersion=1") && coverage.contains("name=demo") &&
                coverage.contains("file ") && coverage.contains("arch=arm64-v8a")
            (excluded && retained) to "coverage carries identity+files, never provenance/signature"
        }

        cases += caseOf("H. the signed-coverage text still parses to a valid unsigned manifest") {
            val reparsed = PackageManifest.parse(sample.signedCoverageText)
            (reparsed.provenance.isEmpty() && reparsed.signature == null &&
                reparsed.files == sample.files && reparsed.name == "demo") to
                "coverage manifest is a valid manifest minus provenance/signature"
        }

        // ---- I. LAYOUT. ----

        cases += caseOf("I. package layout constants match the audited future storage plan") {
            (PackageLayout.PACKAGES_RELATIVE_DIR == "userspace/packages" &&
                PackageLayout.PACKAGE_METADATA_RELATIVE_DIR == "metadata/packages" &&
                PackageLayout.STAGING_PACKAGES_RELATIVE_DIR == "tmp/staging-packages" &&
                PackageLayout.BACKUP_PACKAGES_RELATIVE_DIR == "tmp/backup-packages") to
                "userspace/packages, metadata/packages, tmp/staging-packages, tmp/backup-packages"
        }

        cases += caseOf("I. the base artifact directory is never under the package layout") {
            val baseDir = "${PackageLayout.USERSPACE_DIR}/${PackageLayout.BASE_DIR}"
            val packageDirs = listOf(
                PackageLayout.PACKAGES_RELATIVE_DIR,
                PackageLayout.PACKAGE_METADATA_RELATIVE_DIR,
                PackageLayout.STAGING_PACKAGES_RELATIVE_DIR,
                PackageLayout.BACKUP_PACKAGES_RELATIVE_DIR,
            )
            val noneInsideBase = packageDirs.none { it.startsWith("$baseDir/") }
            (baseDir == "userspace/base" && noneInsideBase) to
                "optional-package state lives outside userspace/base"
        }

        cases += caseOf("I. packageDirectory composes a safe per-package path or null") {
            val valid = PackageLayout.packageDirectory("demo") == "userspace/packages/demo"
            val invalid = PackageLayout.packageDirectory("") == null &&
                PackageLayout.packageDirectory("..") == null &&
                PackageLayout.packageDirectory("Bad") == null &&
                PackageLayout.packageDirectory("a b") == null
            (valid && invalid) to
                "demo -> userspace/packages/demo; empty/../Bad/space -> null"
        }

        return PackageManifestSelfCheckReport(cases)
    }

    /** One payload file reused by single-file manifest constructions. */
    private fun oneFile(): List<PackageFileEntry> =
        listOf(PackageFileEntry(path = "a", sha256 = EXEC_SHA))

    /** The sample's frozen canonical text, written independently of the encoder
     * (line by line, in the exact expected order) so a mismatch is a real bug. */
    private fun expectedSampleCanonicalText(): String = listOf(
        "formatVersion=1",
        "name=demo",
        "version=1.0.0",
        "arch=arm64-v8a",
        "file bin/demo $EXEC_SHA exec",
        "file data/config.txt $CONFIG_SHA",
        "file share/notes.txt $NOTES_SHA",
        "depends=demo-base",
        "conflicts=demo-old=1.0.0",
        "provenance.androidApi=26",
        "provenance.ndk=28.2.13676358",
    ).joinToString(separator = "\n", postfix = "\n")

    /** Joins already-formatted canonical records into one text block. */
    private fun textOf(vararg lines: String): String =
        lines.joinToString(separator = "\n", postfix = "\n")

    /** Adds one genuine assertion case; the block returns a `(expectedMet, detail)` pair. */
    private fun caseOf(
        label: String,
        assert: () -> Pair<Boolean, String>,
    ): PackageManifestSelfCheckCase {
        val (expectedMet, detail) = assert()
        return PackageManifestSelfCheckCase(label = label, expectedMet = expectedMet, detail = detail)
    }

    /** Runs [block] and reports true only when it throws a [PackageManifestException]. */
    private fun rejected(block: () -> Unit): Pair<Boolean, String> =
        try {
            block()
            false to "expected a PackageManifestException but construction/parse succeeded"
        } catch (expected: PackageManifestException) {
            true to "rejected as expected: ${expected.message}"
        }
}
