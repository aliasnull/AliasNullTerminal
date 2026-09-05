// AliasNull base-userspace probe executable (Part 27-S1).
//
// PURPOSE
//   The first genuinely executable AliasNull base-userspace component. Its only
//   jobs are to prove the arm64 userspace execution pipeline end to end and, in
//   its controlled-environment mode, to prove the controlled base-execution
//   environment (Part 27-T1): it is a real ELF executable built from source,
//   prints deterministic lines to stdout, and exits 0. It must never become a
//   JNI library, a shell, a package manager, or anything an Android app loads
//   with System.loadLibrary.
//
//   The executable has exactly two modes, selected solely by whether the
//   environment override it is launched under equals the fixed marker:
//
//     DEFAULT (no ALIASNULL_BASE_ENV, or a different value): prints one
//       deterministic line, "AliasNull base userspace OK", and exits 0. This is
//       the byte-stable output the base-executable launch (Part 27-S2) asserts;
//       the default-mode source path is unchanged from Part 27-S1/S2.
//
//     CONTROLLED ENVIRONMENT (ALIASNULL_BASE_ENV exactly equal to the fixed
//       marker "AliasNull controlled base environment", which is exactly the one
//       override [NativeExecutionPolicy.baseExecutionEnvironmentOverrides]
//       allows): prints the deterministic header line "AliasNull base
//       environment OK", then the real working directory the child was started
//       in ("cwd=<canonical path>") and the override itself echoed back
//       ("ALIASNULL_BASE_ENV=<marker>"), then exits 0. The runtime's
//       base-execution-environment test asserts those exact values on stdout, so
//       it proves the controlled working directory and the one environment
//       override were genuinely applied - never a claim about an environment the
//       executable did not actually observe.
//
//   The two markers below are a fixed cross-language contract with
//   NativeExecutionPolicy.kt (BASE_ENVIRONMENT_VAR / BASE_ENVIRONMENT_MARKER /
//   BASE_ENVIRONMENT_STDOUT_TOKEN) and the test in NativeProcessTestKind.kt; if
//   they ever diverge, the controlled-environment test fails loudly rather than
//   passing vacuously.
//
// PROVENANCE
//   Source project:  AliasNull (this repository)
//   Source file:     app/src/main/cpp/aliasnull_base_probe.cpp
//   Copyright:       AliasNull project authors
//   License:         same terms as the AliasNull project source in which this
//                    file lives; refer to the repository LICENSE.
//   Version:         1 (the executable's identity/output is unchanged; the
//                    artifact-level re-versioning on content change is the
//                    bundled userspace VERSION file, not this header)
//   Target:          Android arm64-v8a (AArch64), Bionic libc
//   Build system:    CMake, using the Android NDK's android.toolchain.cmake
//                    (see the `aliasnull_base_probe` target in the sibling
//                    CMakeLists.txt). CI builds it standalone with the same NDK
//                    used for libaliasnull_runtime.so / libaliasnull_an_shell_core.so.
//   Output:          an Android PIE ELF executable (dynamic, Bionic).
//
//   No third-party code is used. No timestamps, absolute source paths, usernames
//   or machine data are embedded by the program itself.

#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>

namespace {

// Fixed cross-language contract; see the header comment. These never vary with
// the build and are never read from user input.
constexpr const char* kEnvironmentVariable = "ALIASNULL_BASE_ENV";
constexpr const char* kEnvironmentMarker = "AliasNull controlled base environment";
constexpr const char* kEnvironmentHeader = "AliasNull base environment OK";
constexpr const char* kDefaultOutput = "AliasNull base userspace OK";

}  // namespace

int main() {
    const char* marker = std::getenv(kEnvironmentVariable);
    if (marker != nullptr && std::strcmp(marker, kEnvironmentMarker) == 0) {
        // Controlled base-execution-environment mode: report the real cwd and
        // the one override the child actually observed. getcwd cannot fail here
        // because the native runner chdir'd into a validated directory before
        // exec; if it somehow did, fail loudly and deterministically.
        char cwd[PATH_MAX];
        if (std::getcwd(cwd, sizeof(cwd)) == nullptr) {
            std::fputs("AliasNull base environment FAILED: could not determine the working directory\n", stdout);
            std::fflush(stdout);
            return 1;
        }
        std::fputs(kEnvironmentHeader, stdout);
        std::fputc('\n', stdout);
        std::fprintf(stdout, "cwd=%s\n", cwd);
        std::fprintf(stdout, "%s=%s\n", kEnvironmentVariable, marker);
        std::fflush(stdout);
        return 0;
    }
    std::fputs(kDefaultOutput, stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
    return 0;
}
