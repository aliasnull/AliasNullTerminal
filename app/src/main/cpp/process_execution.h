// AliasNull native runtime: real one-shot process execution.
//
// This is the genuine external-process runner foundation (Part 27-O). It lives
// in libaliasnull_runtime.so and is reached through the existing
// NativeRuntimeBridge JNI owner. It is deliberately NOT a shell, NOT a command
// interpreter, NOT a PTY and NOT a Linux userspace: it launches one requested
// argv with POSIX fork + execve, connects real stdin/stdout/stderr pipes, waits
// for the child to terminate and returns the genuinely captured output plus the
// real termination outcome. Nothing is parsed here and no command language is
// interpreted; the caller supplies a fully-formed argument vector.
#ifndef ALIASNULL_RUNTIME_PROCESS_EXECUTION_H
#define ALIASNULL_RUNTIME_PROCESS_EXECUTION_H

#include <cstdint>
#include <string>
#include <vector>

namespace aliasnull_runtime {

/// Lifecycle outcome of one process-run request. The numeric values are a fixed
/// cross-language contract shared with NativeProcessOutcome on the Kotlin side
/// and with the payload codec; they must not be reordered.
enum class ProcessOutcome : std::int32_t {
  /// The child terminated normally; exit_code is meaningful.
  ExitedNormally = 0,
  /// The child was terminated by a signal; term_signal is meaningful.
  TerminatedBySignal = 1,
  /// The requested executable could not be resolved or started (no exec ran).
  LaunchFailed = 2,
  /// The runner itself failed before/while managing the child (pipe/fork/wait).
  InternalError = 3,
};

/// One process-run request. argv is the single authority for what runs; there is
/// no shell string anywhere. working_directory (when has_working_directory is
/// true) becomes the child's working directory. environment_overrides are
/// "KEY=VALUE" entries applied over the inherited environment. When has_stdin is
/// true, stdin_bytes are written to the child's stdin once before it closes;
/// otherwise the child simply sees end-of-file on stdin.
struct ProcessLaunch {
  std::vector<std::string> argv;          // non-empty; argv[0] is the executable
  bool has_working_directory = false;
  std::string working_directory;
  std::vector<std::string> environment_overrides;  // "KEY=VALUE"
  bool has_stdin = false;
  std::vector<std::uint8_t> stdin_bytes;
};

/// Structured, honest result of one process run. stdout and stderr are kept
/// separate; exit_code/term_signal are populated only for the outcome that makes
/// them meaningful. error_message and errno_code carry launch/internal detail and
/// never pretend a failed launch was an executed program's exit status.
struct ProcessResult {
  ProcessOutcome outcome = ProcessOutcome::InternalError;
  int exit_code = 0;
  int term_signal = 0;
  int errno_code = 0;
  std::string stdout_bytes;
  std::string stderr_bytes;
  std::string error_message;
};

/// Runs one child process to completion and captures its output. Blocking: it
/// returns only after the child has terminated. Free of JNI and of any global
/// mutable runner state, so it is safe to call concurrently from several
/// threads. Returns true when the process genuinely started and then exited
/// (normally or by signal); false for LaunchFailed/InternalError.
bool run_process(const ProcessLaunch& launch, ProcessResult& result);

}  // namespace aliasnull_runtime

#endif  // ALIASNULL_RUNTIME_PROCESS_EXECUTION_H
