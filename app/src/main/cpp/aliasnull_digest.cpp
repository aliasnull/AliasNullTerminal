// AliasNull base-userspace SHA-256 file-digest component (Part 27-T2).
//
// PURPOSE
//   The first genuinely reusable real userspace component after the
//   aliasnull_base_probe proof executable (Part 27-S1/S2/T1). It is a real,
//   dependency-free, read-only file-digest executable: it computes the SHA-256
//   of each file it is given and prints one deterministic line per file. It is a
//   normal executable - never a JNI library, never loaded with
//   System.loadLibrary, and never part of libaliasnull_runtime.so.
//
//   SHA-256 is implemented here from the public FIPS 180-4 specification
//   (National Institute of Standards and Technology, "Secure Hash Standard").
//   The implementation is original AliasNull-authored code written from that
//   standard; it does not copy any third-party implementation and depends on no
//   cryptographic library. The round constants are the fractional cube-root
//   constants defined by the standard.
//
//   The executable has exactly two modes, selected solely by its environment:
//
//     DEFAULT (no ALIASNULL_DIGEST_ROOT in the environment): a genuine reusable
//       file-digest tool. For each file named on the command line it prints
//       "<64 lowercase hex>  <the path as named>" and exits 0. A file that
//       cannot be opened is reported honestly on stderr and the executable
//       exits non-zero. This mode exists so the component is a real reusable
//       utility; the AliasNull runtime never invokes it (the runtime only ever
//       runs the controlled mode below through the fixed diagnostic).
//
//     CONTROLLED (ALIASNULL_DIGEST_ROOT is set to the installed base-userspace
//       root): the fixed base-verification mode the runtime's Base Digest
//       diagnostic runs. The executable hashes EXACTLY the manifest files of the
//       installed AliasNull base userspace under that root - the same relative
//       file set and the same order as BaseUserspaceArtifact.FILES - and prints
//       one "<digest>  <name>" line per file, then exits 0. The Kotlin-side
//       diagnostic parses that output strictly and requires every digest to
//       equal the BaseUserspaceArtifact manifest value, so a real userspace
//       executable independently verifies the installed base against the
//       manifest the bootstrap already verified. The root value comes ONLY from
//       the verified execution model set by the runtime - never from UI or user
//       input.
//
//   The controlled-mode file set below is a fixed cross-language contract with
//   BaseUserspaceArtifact.kt (the manifest insertion order of FILES); the
//   diagnostic asserts the output file set equals that manifest exactly, so if
//   the two ever diverge the test fails loudly rather than passing vacuously.
//
// PROVENANCE
//   Source project:  AliasNull (this repository)
//   Source file:     app/src/main/cpp/aliasnull_digest.cpp
//   Copyright:       AliasNull project authors
//   License:         same terms as the AliasNull project source in which this
//                    file lives; refer to the repository LICENSE.
//   Version:         1
//   Target:          Android arm64-v8a (AArch64), Bionic libc
//   Build system:    CMake, using the Android NDK's android.toolchain.cmake
//                    (see the `aliasnull_digest` target in the sibling
//                    CMakeLists.txt). CI builds it standalone with the same NDK
//                    used for aliasnull_base_probe and libaliasnull_runtime.so.
//   Output:          an Android PIE ELF executable (dynamic, Bionic).
//
//   No third-party code is used. No timestamps, absolute source paths, usernames
//   or machine data are embedded by the program itself.

#include <climits>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <errno.h>

namespace {

// ---- FIPS 180-4 SHA-256 ----

// The sixty-four 32-bit round constants K: the first 32 bits of the fractional
// parts of the cube roots of the first sixty-four prime numbers (FIPS 180-4
// section 4.2.2). Transcribed from the standard; verified against the standard
// SHA-256 test vectors.
constexpr std::uint32_t kRoundConstants[64] = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
};

// The eight initial hash values H: the first 32 bits of the fractional parts
// of the square roots of the first eight prime numbers (FIPS 180-4 5.3.3).
constexpr std::uint32_t kInitialHash[8] = {
    0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
    0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U,
};

constexpr int kBlockBytes = 64;
constexpr int kDigestBytes = 32;

std::uint32_t Rotr(std::uint32_t value, int count) {
    return (value >> count) | (value << (32 - count));
}

void Compress(const std::uint8_t block[kBlockBytes], std::uint32_t state[8]) {
    std::uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = (static_cast<std::uint32_t>(block[i * 4]) << 24) |
               (static_cast<std::uint32_t>(block[i * 4 + 1]) << 16) |
               (static_cast<std::uint32_t>(block[i * 4 + 2]) << 8) |
               static_cast<std::uint32_t>(block[i * 4 + 3]);
    }
    for (int i = 16; i < 64; ++i) {
        const std::uint32_t s0 =
            Rotr(w[i - 15], 7) ^ Rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        const std::uint32_t s1 =
            Rotr(w[i - 2], 17) ^ Rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    std::uint32_t a = state[0];
    std::uint32_t b = state[1];
    std::uint32_t c = state[2];
    std::uint32_t d = state[3];
    std::uint32_t e = state[4];
    std::uint32_t f = state[5];
    std::uint32_t g = state[6];
    std::uint32_t h = state[7];
    for (int i = 0; i < 64; ++i) {
        const std::uint32_t s1 = Rotr(e, 6) ^ Rotr(e, 11) ^ Rotr(e, 25);
        const std::uint32_t ch = (e & f) ^ (~e & g);
        const std::uint32_t temp1 = h + s1 + ch + kRoundConstants[i] + w[i];
        const std::uint32_t s0 = Rotr(a, 2) ^ Rotr(a, 13) ^ Rotr(a, 22);
        const std::uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        const std::uint32_t temp2 = s0 + maj;
        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }
    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
    state[4] += e;
    state[5] += f;
    state[6] += g;
    state[7] += h;
}

struct Sha256 {
    std::uint32_t state[8];
    std::uint64_t total_bytes;
    std::uint8_t block[kBlockBytes];
    std::size_t block_len;

    void Init() {
        for (int i = 0; i < 8; ++i) {
            state[i] = kInitialHash[i];
        }
        total_bytes = 0;
        block_len = 0;
    }

    void Update(const std::uint8_t* data, std::size_t len) {
        total_bytes += len;
        if (block_len > 0) {
            const std::size_t need = kBlockBytes - block_len;
            const std::size_t take = len < need ? len : need;
            std::memcpy(block + block_len, data, take);
            block_len += take;
            data += take;
            len -= take;
            if (block_len == kBlockBytes) {
                Compress(block, state);
                block_len = 0;
            }
        }
        while (len >= kBlockBytes) {
            Compress(data, state);
            data += kBlockBytes;
            len -= kBlockBytes;
        }
        if (len > 0) {
            std::memcpy(block, data, len);
            block_len = len;
        }
    }

    // Appends the standard 0x80 padding and the big-endian 64-bit bit length,
    // then emits the final digest into out (32 bytes). No further Update calls
    // are valid afterwards.
    void Finish(std::uint8_t out[kDigestBytes]) {
        const std::uint64_t bit_length = total_bytes * 8;
        const std::uint8_t pad = 0x80;
        Update(&pad, 1);
        const std::uint8_t zero = 0;
        while (block_len != 56) {
            Update(&zero, 1);
        }
        std::uint8_t length_bytes[8];
        for (int i = 0; i < 8; ++i) {
            length_bytes[i] =
                static_cast<std::uint8_t>((bit_length >> (56 - i * 8)) & 0xffU);
        }
        Update(length_bytes, 8);
        for (int i = 0; i < 8; ++i) {
            out[i * 4] = static_cast<std::uint8_t>((state[i] >> 24) & 0xffU);
            out[i * 4 + 1] = static_cast<std::uint8_t>((state[i] >> 16) & 0xffU);
            out[i * 4 + 2] = static_cast<std::uint8_t>((state[i] >> 8) & 0xffU);
            out[i * 4 + 3] = static_cast<std::uint8_t>(state[i] & 0xffU);
        }
    }
};

// ---- File hashing ----

constexpr std::size_t kReadChunkBytes = 65536;

// Streams [path] read-only in bounded chunks through the SHA-256 state. Returns
// true on success with digest filled; on failure writes one honest error line
// to stderr and returns false. The file is never modified.
bool HashFile(const char* path, std::uint8_t digest[kDigestBytes]) {
    FILE* file = std::fopen(path, "rb");
    if (file == nullptr) {
        std::fprintf(stderr, "aliasnull_digest: cannot open '%s': %s\n", path,
                     std::strerror(errno));
        return false;
    }
    Sha256 sha;
    sha.Init();
    std::uint8_t buffer[kReadChunkBytes];
    bool ok = true;
    for (;;) {
        const std::size_t read = std::fread(buffer, 1, sizeof(buffer), file);
        if (read > 0) {
            sha.Update(buffer, read);
        }
        if (read < sizeof(buffer)) {
            if (std::ferror(file)) {
                std::fprintf(stderr, "aliasnull_digest: error reading '%s'\n", path);
                ok = false;
            }
            break;
        }
    }
    if (std::fclose(file) != 0 && ok) {
        std::fprintf(stderr, "aliasnull_digest: error closing '%s'\n", path);
        ok = false;
    }
    if (!ok) {
        return false;
    }
    sha.Finish(digest);
    return true;
}

// Prints digest as 64 lowercase hex characters followed by two spaces and
// [label], then a newline - the deterministic per-file output line.
void PrintDigestLine(const std::uint8_t digest[kDigestBytes], const char* label) {
    static const char kHex[] = "0123456789abcdef";
    char out[2 * kDigestBytes];
    for (int i = 0; i < kDigestBytes; ++i) {
        out[i * 2] = kHex[(digest[i] >> 4) & 0xf];
        out[i * 2 + 1] = kHex[digest[i] & 0xf];
    }
    std::fwrite(out, 1, sizeof(out), stdout);
    std::fputs("  ", stdout);
    std::fputs(label, stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
}

// Fixed cross-language contract; see the header comment. Never varies with the
// build and is never read from user input.
constexpr const char* kRootEnvironmentVariable = "ALIASNULL_DIGEST_ROOT";

// The manifest-relative files the controlled mode hashes, in EXACTLY the same
// order as BaseUserspaceArtifact.FILES. When the artifact changes, this list
// must be kept in lock-step with the manifest or the Base Digest diagnostic
// fails loudly (the diagnostic asserts the output file set equals FILES).
constexpr const char* const kControlledFiles[] = {
    "VERSION",
    "ARCH",
    "DESCRIPTION",
    "PROVENANCE.txt",
    "LICENSE.txt",
    "aliasnull_base_probe",
    "aliasnull_digest",
};

// Hashes every kControlledFiles entry under [root] and prints a digest line per
// file. Returns the process exit code.
int RunControlled(const char* root) {
    for (const char* name : kControlledFiles) {
        char path[PATH_MAX];
        const int written =
            std::snprintf(path, sizeof(path), "%s/%s", root, name);
        if (written < 0 || static_cast<std::size_t>(written) >= sizeof(path)) {
            std::fprintf(stderr,
                         "aliasnull_digest: controlled path too long for '%s/%s'\n",
                         root, name);
            return 1;
        }
        std::uint8_t digest[kDigestBytes];
        if (!HashFile(path, digest)) {
            return 1;
        }
        PrintDigestLine(digest, name);
    }
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    const char* root = std::getenv(kRootEnvironmentVariable);
    if (root != nullptr) {
        if (root[0] == '\0') {
            std::fprintf(stderr,
                         "aliasnull_digest: %s is set but empty\n",
                         kRootEnvironmentVariable);
            return 2;
        }
        return RunControlled(root);
    }
    if (argc < 2) {
        std::fprintf(stderr, "usage: aliasnull_digest FILE...\n");
        return 2;
    }
    for (int i = 1; i < argc; ++i) {
        std::uint8_t digest[kDigestBytes];
        if (!HashFile(argv[i], digest)) {
            return 1;
        }
        PrintDigestLine(digest, argv[i]);
    }
    return 0;
}
