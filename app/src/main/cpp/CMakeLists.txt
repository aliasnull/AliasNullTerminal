# AliasNull native runtime library.
#
# One small shared library, libaliasnull_runtime.so. It builds the bootstrap
# foundation (initialization, directory validation, shutdown, metadata) and,
# since Part 27-O, a real one-shot process-runner capability (fork + execve of
# a caller-supplied argv with real stdin/stdout/stderr capture, wait and exit
# status -- see process_execution.cpp). It does NOT contain a command
# interpreter, a shell, a PTY, or a Linux userspace; those belong to later
# phases. AGP builds it for the module's configured ABIs from the NDK the
# build environment provides.
cmake_minimum_required(VERSION 3.22.1)
project(aliasnull_runtime CXX)

add_library(aliasnull_runtime SHARED
    aliasnull_runtime.cpp
    process_execution.cpp
    process_execution_jni.cpp)

# android logcat support used for honest bootstrap reporting.
target_link_libraries(aliasnull_runtime log)

# C++17 for the standard library and C++17 STL facilities used by the bootstrap
# and the process runner (std::thread, std::vector, std::string).
target_compile_features(aliasnull_runtime PRIVATE cxx_std_17)

# Part 27-S1: the AliasNull base-userspace probe executable.
#
# A NORMAL executable target (not SHARED, no JNI). AGP packages only SHARED
# libraries into the APK and this target is never loaded with System.loadLibrary,
# so it has no effect on libaliasnull_runtime.so or the APK. CI builds this
# target STANDALONE (its own cmake configure into build/userspace-probe, decoupled
# from the AGP/Android build) from the same NDK toolchain and uploads the
# resulting arm64 ELF as a proof artifact; the future base-userspace integration
# milestone decides when (if ever) it joins the APK. It links only Bionic libc.
add_executable(aliasnull_base_probe aliasnull_base_probe.cpp)
target_compile_features(aliasnull_base_probe PRIVATE cxx_std_17)

# Part 27-T2: the AliasNull base-userspace SHA-256 file-digest component.
#
# A NORMAL executable target (not SHARED, no JNI), exactly like
# aliasnull_base_probe: AGP packages only SHARED libraries into the APK and this
# target is never loaded with System.loadLibrary, so it has no effect on
# libaliasnull_runtime.so or the APK. CI builds this target STANDALONE (its own
# cmake configure into build/userspace-digest, decoupled from the AGP/Android
# build) from the same NDK toolchain, validates it, and uploads the resulting
# arm64 ELF as the committed base-userspace asset; the base bootstrap verifies
# it (SHA-256, 64-bit AArch64 ELF, executable permission) like every other base
# file. It links only Bionic libc. Its only job is to hash files read-only and
# print deterministic SHA-256 lines; it is never a command executor.
add_executable(aliasnull_digest aliasnull_digest.cpp)
target_compile_features(aliasnull_digest PRIVATE cxx_std_17)
