// AliasNull base-userspace probe executable (Part 27-S1).
//
// PURPOSE
//   The first genuinely executable AliasNull base-userspace component. Its only
//   job is to prove the arm64 userspace execution pipeline end to end: it is a
//   real ELF executable built from source, prints one deterministic line to
//   stdout, and exits 0. It must never become a JNI library, a shell, a package
//   manager, or anything an Android app loads with System.loadLibrary.
//
// PROVENANCE
//   Source project:  AliasNull (this repository)
//   Source file:     app/src/main/cpp/aliasnull_base_probe.cpp
//   Copyright:       AliasNull project authors
//   License:         same terms as the AliasNull project source in which this
//                    file lives; refer to the repository LICENSE.
//   Version:         1
//   Target:          Android arm64-v8a (AArch64), Bionic libc
//   Build system:    CMake, using the Android NDK's android.toolchain.cmake
//                    (see the `aliasnull_base_probe` target in the sibling
//                    CMakeLists.txt). CI builds it standalone with the same NDK
//                    used for libaliasnull_runtime.so / libaliasnull_an_shell_core.so.
//   Output:          an Android PIE ELF executable (dynamic, Bionic).
//
//   No third-party code is used. No timestamps, absolute source paths, usernames
//   or machine data are embedded by the program itself.

#include <cstdio>

int main() {
    // Deterministic, unambiguous output that identifies this AliasNull
    // userspace executable - never an Android /system/bin message.
    std::fputs("AliasNull base userspace OK\n", stdout);
    std::fflush(stdout);
    return 0;
}
