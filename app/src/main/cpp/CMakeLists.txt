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
