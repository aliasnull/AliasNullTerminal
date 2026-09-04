// AliasNull native runtime: real one-shot process execution (Part 27-O).
//
// Implements run_process: fork + execve a requested argv with real stdin,
// stdout and stderr pipes, drain stdout and stderr concurrently so a child that
// produces a lot of output on both cannot deadlock, wait for the child and
// report the real termination outcome. This is genuine process execution: every
// output byte and exit status comes from the actual child, never from a mock or
// a hardcoded result.
//
// Scope honesty: this is a one-shot runner. It does NOT provide a PTY,
// streaming/live output, interactive stdin, process groups, job control or a
// Linux userspace. A child that closes its stdio and keeps running is waited on
// until it exits (run-until-exit semantics); a child that spawns a background
// grandchild that keeps the pipe write ends open will keep this runner draining
// until those ends close. Both are documented boundaries of this foundation.
//
// Safety notes
// ------------
// * The child runs only async-signal-safe operations (chdir, dup2, close,
//   execve, write, _exit) after fork. Executable PATH resolution and the child
//   environment are built in the parent before fork so the child allocates
//   nothing. chdir is technically not on the POSIX async-signal-safe list but
//   does not allocate; it is used here because posix_spawn cannot set a working
//   directory, and it is the standard practice for one-shot launchers.
// * stdout/stderr are drained concurrently with poll(), never sequentially
//   after waitpid, so neither pipe can fill and deadlock while the other is
//   read. stdin (a one-shot payload) is written on a dedicated thread that has
//   SIGPIPE blocked, so a child that exits early cannot terminate the app.
// * A dedicated "launch" pipe distinguishes "the program genuinely started and
//   exited" from "exec itself failed": its write end is close-on-exec, so a
//   successful exec closes it silently (EOF) while an exec failure makes the
//   child write its errno into it before _exit(127). This keeps a real child
//   exit status of 127 distinct from a launch failure.
// * Every pipe is closed on every path; the child is always reaped with
//   waitpid, so no descriptor leaks and no zombie process are left behind.

#include "process_execution.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace aliasnull_runtime {

namespace {

// PATH used when argv[0] is a bare name with no '/'.
const char kDefaultPath[] = "/system/bin:/vendor/bin";

// Reads the effective PATH: the last environment override named PATH wins,
// otherwise the inherited environment's PATH, otherwise the default above.
std::string effective_path(const ProcessLaunch& launch) {
  // <unistd.h> already declares the process-global `environ` at global scope
  // (bionic: `extern char** environ;` inside an extern "C" block), so it is
  // referenced as ::environ. A local `extern char** environ;` here would
  // instead declare an anonymous-namespace variable of internal linkage that
  // never resolves to the real bionic symbol.
  std::string inherited;
  for (char** e = ::environ; e != nullptr && *e != nullptr; ++e) {
    const char* entry = *e;
    if (std::strncmp(entry, "PATH=", 5) == 0) {
      inherited.assign(entry + 5);
      break;
    }
  }
  std::string chosen = inherited;
  for (const std::string& override : launch.environment_overrides) {
    if (override.compare(0, 5, "PATH=") == 0) {
      chosen.assign(override, 5, std::string::npos);
    }
  }
  if (chosen.empty()) chosen = kDefaultPath;
  return chosen;
}

// Canonicalizes an existing candidate into an absolute path (resolving
// symlinks and any relative components against the current working directory)
// so the child can exec it even after chdir to the request's working directory.
// Returns empty on failure, setting err_out.
std::string canonical_executable(const std::string& candidate, int& err_out) {
  char* resolved = ::realpath(candidate.c_str(), nullptr);
  if (resolved == nullptr) {
    err_out = errno;
    return std::string();
  }
  std::string absolute(resolved);
  ::free(resolved);
  if (::access(absolute.c_str(), X_OK) != 0) {
    err_out = errno;
    return std::string();
  }
  return absolute;
}

// Resolves argv[0] to an absolute path for execve. A name containing '/' is
// canonicalized directly. A bare name is searched along the effective PATH and
// the first executable candidate is canonicalized. Returns empty on failure,
// setting errno to a meaningful value (ENOENT when nothing was found, EACCES
// when a candidate exists but is not executable).
std::string resolve_executable(const ProcessLaunch& launch, int& err_out) {
  const std::string& name = launch.argv.front();
  if (name.find('/') != std::string::npos) {
    return canonical_executable(name, err_out);
  }
  const std::string path = effective_path(launch);
  std::size_t start = 0;
  while (start <= path.size()) {
    const std::size_t end = path.find(':', start);
    const std::string dir =
        path.substr(start, end == std::string::npos ? std::string::npos : end - start);
    if (!dir.empty()) {
      std::string candidate = dir;
      if (candidate.back() != '/') candidate += '/';
      candidate += name;
      if (::access(candidate.c_str(), X_OK) == 0) {
        std::string absolute = canonical_executable(candidate, err_out);
        if (!absolute.empty()) return absolute;
      } else if (errno != ENOENT && err_out == 0) {
        err_out = errno;
      }
    }
    if (end == std::string::npos) break;
    start = end + 1;
  }
  if (err_out == 0) err_out = ENOENT;
  return std::string();
}

// Builds the child environment as "inherited environ, overlaid with overrides",
// into envp (pointer array) backed by env_storage (owned strings). All storage
// is allocated in the parent before fork and lives until execve.
void build_environment(const ProcessLaunch& launch,
                       std::vector<std::string>& env_storage,
                       std::vector<char*>& envp) {
  for (char** e = ::environ; e != nullptr && *e != nullptr; ++e) {
    env_storage.emplace_back(*e);
  }
  for (const std::string& override : launch.environment_overrides) {
    const std::size_t eq = override.find('=');
    if (eq == std::string::npos || eq == 0) continue;  // malformed; Kotlin also validates
    const std::string key = override.substr(0, eq);
    bool replaced = false;
    for (std::string& existing : env_storage) {
      if (existing.compare(0, key.size(), key) == 0 &&
          existing.size() > key.size() && existing[key.size()] == '=') {
        existing = override;
        replaced = true;
        break;
      }
    }
    if (!replaced) env_storage.push_back(override);
  }
  envp.reserve(env_storage.size() + 1);
  for (const std::string& entry : env_storage) {
    envp.push_back(const_cast<char*>(entry.c_str()));
  }
  envp.push_back(nullptr);
}

// Child-only code, run after fork. Only async-signal-safe operations plus the
// read-only inspection of already-built strings/vectors. Never returns on
// success (execve). On any setup failure writes errno to launch_write_fd and
// _exit(127) so the parent can tell a launch failure from a real exit status.
void child_after_fork(int stdin_read_fd, int stdin_write_fd, int stdout_read_fd,
                      int stdout_write_fd, int stderr_read_fd, int stderr_write_fd,
                      int launch_read_fd, int launch_write_fd,
                      const std::string& working_directory, bool has_working_directory,
                      const char* exec_path, char* const* argv, char* const* envp) {
  const auto fail = [&](int error_code) {
    const int code = error_code;
    const ssize_t ignored = ::write(launch_write_fd, &code, sizeof(code));
    (void)ignored;
    ::_exit(127);
  };

  if (has_working_directory && ::chdir(working_directory.c_str()) != 0) {
    fail(errno);
  }
  if (::dup2(stdin_read_fd, STDIN_FILENO) < 0) fail(errno);
  if (::dup2(stdout_write_fd, STDOUT_FILENO) < 0) fail(errno);
  if (::dup2(stderr_write_fd, STDERR_FILENO) < 0) fail(errno);

  // Close every inherited pipe end except the two real stdio fds and the launch
  // channel. All of these are >= 3 (0/1/2 were open before any pipe()).
  ::close(stdin_read_fd);
  ::close(stdin_write_fd);
  ::close(stdout_read_fd);
  ::close(stdout_write_fd);
  ::close(stderr_read_fd);
  ::close(stderr_write_fd);
  ::close(launch_read_fd);

  ::execve(exec_path, argv, envp);
  fail(errno);  // only reached when execve itself failed
}

// Sets a pipe's write end close-on-exec so a successful exec silently closes it.
void set_close_on_exec(int fd) {
  const int flags = ::fcntl(fd, F_GETFD);
  if (flags >= 0) {
    (void)::fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
  }
}

void close_pipe(int fd, int* tracking) {
  if (fd >= 0) {
    ::close(fd);
    *tracking = -1;
  }
}

}  // namespace

bool run_process(const ProcessLaunch& launch, ProcessResult& result) {
  if (launch.argv.empty() || launch.argv.front().empty()) {
    result.outcome = ProcessOutcome::InternalError;
    result.errno_code = EINVAL;
    result.error_message = "The process request has an empty argv.";
    return false;
  }

  int stdin_pipe[2] = {-1, -1};
  int stdout_pipe[2] = {-1, -1};
  int stderr_pipe[2] = {-1, -1};
  int launch_pipe[2] = {-1, -1};

  const auto close_created = [&]() {
    for (int fd : {stdin_pipe[0], stdin_pipe[1], stdout_pipe[0], stdout_pipe[1],
                   stderr_pipe[0], stderr_pipe[1], launch_pipe[0], launch_pipe[1]}) {
      if (fd >= 0) ::close(fd);
    }
  };

  if (::pipe(stdin_pipe) != 0 || ::pipe(stdout_pipe) != 0 ||
      ::pipe(stderr_pipe) != 0 || ::pipe(launch_pipe) != 0) {
    const int saved_errno = errno;
    close_created();
    result.outcome = ProcessOutcome::InternalError;
    result.errno_code = saved_errno;
    result.error_message =
        std::string("Could not create the process pipes: ") + std::strerror(saved_errno);
    return false;
  }
  set_close_on_exec(launch_pipe[1]);

  // Resolve the executable in the parent (before fork) so the child only execs.
  int resolve_errno = 0;
  const std::string exec_path = resolve_executable(launch, resolve_errno);
  if (exec_path.empty()) {
    const int code = resolve_errno != 0 ? resolve_errno : ENOENT;
    close_created();
    result.outcome = ProcessOutcome::LaunchFailed;
    result.errno_code = code;
    result.error_message =
        "Could not resolve the requested executable '" + launch.argv.front() + "': " +
        std::strerror(code);
    return false;
  }

  // Build argv/envp pointer arrays in the parent (the child must not allocate).
  std::vector<char*> child_argv;
  child_argv.reserve(launch.argv.size() + 1);
  for (const std::string& arg : launch.argv) {
    child_argv.push_back(const_cast<char*>(arg.c_str()));
  }
  child_argv.push_back(nullptr);

  std::vector<std::string> env_storage;
  std::vector<char*> child_envp;
  build_environment(launch, env_storage, child_envp);

  const pid_t pid = ::fork();
  if (pid < 0) {
    const int saved_errno = errno;
    close_created();
    result.outcome = ProcessOutcome::InternalError;
    result.errno_code = saved_errno;
    result.error_message =
        std::string("Could not fork the child process: ") + std::strerror(saved_errno);
    return false;
  }

  if (pid == 0) {
    child_after_fork(stdin_pipe[0], stdin_pipe[1], stdout_pipe[0], stdout_pipe[1],
                     stderr_pipe[0], stderr_pipe[1], launch_pipe[0], launch_pipe[1],
                     launch.working_directory, launch.has_working_directory,
                     exec_path.c_str(), child_argv.data(), child_envp.data());
    // Never reached: child_after_fork either execs or _exit(127).
    ::_exit(127);
  }

  // Parent: close the child's ends of every pipe. Keep stdin write (until the
  // payload is written), stdout/stderr read (to drain) and the launch read end.
  close_pipe(stdin_pipe[0], &stdin_pipe[0]);
  close_pipe(stdout_pipe[1], &stdout_pipe[1]);
  close_pipe(stderr_pipe[1], &stderr_pipe[1]);
  close_pipe(launch_pipe[1], &launch_pipe[1]);

  // One-shot stdin: write the payload on a dedicated thread (SIGPIPE blocked so
  // a child that exits early surfaces EPIPE instead of killing the app), then
  // close the write end. No bytes means the write end closes immediately and the
  // child sees end-of-file on stdin.
  const bool write_stdin = launch.has_stdin && !launch.stdin_bytes.empty();
  const int stdin_write_fd = stdin_pipe[1];
  bool writer_started = false;
  std::thread stdin_writer;
  if (write_stdin) {
    try {
      stdin_writer = std::thread([stdin_write_fd, &launch]() {
        sigset_t set;
        ::sigemptyset(&set);
        ::sigaddset(&set, SIGPIPE);
        (void)::pthread_sigmask(SIG_BLOCK, &set, nullptr);
        const char* data =
            reinterpret_cast<const char*>(launch.stdin_bytes.data());
        const std::size_t length = launch.stdin_bytes.size();
        std::size_t written = 0;
        while (written < length) {
          const ssize_t n = ::write(stdin_write_fd, data + written, length - written);
          if (n > 0) {
            written += static_cast<std::size_t>(n);
          } else if (n < 0 && errno == EINTR) {
            continue;
          } else {
            break;  // EPIPE (child exited/closed stdin) or a real write error
          }
        }
        ::close(stdin_write_fd);
      });
      writer_started = true;
    } catch (...) {
      // Resource exhaustion prevented the writer thread; close the pipe so the
      // child sees end-of-file on stdin and the run still completes honestly.
      ::close(stdin_write_fd);
      stdin_pipe[1] = -1;
    }
  } else {
    ::close(stdin_write_fd);
    stdin_pipe[1] = -1;
  }

  // Drain stdout and stderr concurrently with poll() so neither pipe can fill
  // and deadlock the other while the child is still producing output.
  bool stdout_open = true;
  bool stderr_open = true;
  bool read_failed = false;
  char buffer[16384];
  while (stdout_open || stderr_open) {
    struct pollfd pollfds[2];
    int poll_count = 0;
    int stdout_index = -1;
    int stderr_index = -1;
    if (stdout_open) {
      pollfds[poll_count] = {stdout_pipe[0], static_cast<short>(POLLIN | POLLHUP), 0};
      stdout_index = poll_count;
      ++poll_count;
    }
    if (stderr_open) {
      pollfds[poll_count] = {stderr_pipe[0], static_cast<short>(POLLIN | POLLHUP), 0};
      stderr_index = poll_count;
      ++poll_count;
    }
    const int polled = ::poll(pollfds, static_cast<nfds_t>(poll_count), -1);
    if (polled < 0) {
      if (errno == EINTR) continue;
      read_failed = true;
      break;
    }
    const short read_events = static_cast<short>(POLLIN | POLLHUP | POLLERR | POLLNVAL);
    if (stdout_index >= 0 && (pollfds[stdout_index].revents & read_events) != 0) {
      const ssize_t n = ::read(stdout_pipe[0], buffer, sizeof(buffer));
      if (n > 0) {
        result.stdout_bytes.append(buffer, static_cast<std::size_t>(n));
      } else if (n == 0) {
        stdout_open = false;
      } else if (errno != EINTR) {
        read_failed = true;
        stdout_open = false;
      }
    }
    if (stderr_index >= 0 && (pollfds[stderr_index].revents & read_events) != 0) {
      const ssize_t n = ::read(stderr_pipe[0], buffer, sizeof(buffer));
      if (n > 0) {
        result.stderr_bytes.append(buffer, static_cast<std::size_t>(n));
      } else if (n == 0) {
        stderr_open = false;
      } else if (errno != EINTR) {
        read_failed = true;
        stderr_open = false;
      }
    }
  }

  if (writer_started) {
    stdin_writer.join();
    stdin_pipe[1] = -1;  // the writer closed it
  }
  close_pipe(stdout_pipe[0], &stdout_pipe[0]);
  close_pipe(stderr_pipe[0], &stderr_pipe[0]);

  // Read the launch channel to end of input: a successful exec closed its write
  // end at exec time (EOF, no data) while an exec failure left the child's errno
  // in it. Either way this returns promptly because no other write end exists.
  int launch_errno = 0;
  std::size_t launch_bytes = 0;
  while (launch_bytes < sizeof(launch_errno)) {
    const ssize_t n = ::read(
        launch_pipe[0],
        reinterpret_cast<char*>(&launch_errno) + launch_bytes,
        sizeof(launch_errno) - launch_bytes);
    if (n > 0) {
      launch_bytes += static_cast<std::size_t>(n);
    } else if (n == 0) {
      break;
    } else if (errno != EINTR) {
      break;
    }
  }
  close_pipe(launch_pipe[0], &launch_pipe[0]);

  if (read_failed) {
    // We could not finish draining; still reap the child so no zombie is left.
    result.outcome = ProcessOutcome::InternalError;
    result.errno_code = EIO;
    result.error_message = "Reading the child's output failed before end of stream.";
  } else if (launch_bytes >= sizeof(launch_errno)) {
    result.outcome = ProcessOutcome::LaunchFailed;
    result.errno_code = launch_errno;
    result.error_message =
        std::string("The child could not exec: ") + std::strerror(launch_errno);
  }

  // Reap the child so it can never become a zombie. waitpid is retried on EINTR.
  int wait_status = 0;
  pid_t waited = 0;
  do {
    waited = ::waitpid(pid, &wait_status, 0);
  } while (waited < 0 && errno == EINTR);

  if (result.outcome == ProcessOutcome::InternalError && !read_failed) {
    if (waited < 0) {
      result.errno_code = errno;
      result.error_message =
          std::string("Could not wait for the child: ") + std::strerror(errno);
    } else if (WIFEXITED(wait_status)) {
      result.outcome = ProcessOutcome::ExitedNormally;
      result.exit_code = WEXITSTATUS(wait_status);
    } else if (WIFSIGNALED(wait_status)) {
      result.outcome = ProcessOutcome::TerminatedBySignal;
      result.term_signal = WTERMSIG(wait_status);
    } else {
      result.error_message = "The child ended in an unrecognized wait state.";
    }
  }

  // LaunchFailed: the exec never ran, so the child's own _exit(127) is not a
  // real program exit status; the launch error already carries the truth.
  const bool process_ran =
      result.outcome == ProcessOutcome::ExitedNormally ||
      result.outcome == ProcessOutcome::TerminatedBySignal;
  return process_ran;
}

}  // namespace aliasnull_runtime
