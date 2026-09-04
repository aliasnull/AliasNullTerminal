// AliasNull native runtime: JNI entry point for the one-shot process runner.
//
// This TU maps the Kotlin external declared on the single NativeRuntimeBridge
// owner to the pure runner in process_execution.h/.cpp, and encodes the result
// into the documented little-endian byte payload the Kotlin codec decodes. It is
// deliberately tiny and defensive: invalid requests (null/empty argv, a null or
// malformed environment entry, a null array element) are returned as a
// structured InternalError result, never as a crash, and no raw pointer is ever
// exposed to Kotlin.
//
// JNI symbol: instance method of the Kotlin object
// app.aliasnull.shell.runtime.native.NativeRuntimeBridge (second parameter is
// jobject), name nativeRunProcess. The name is case/underscore free so no `_1`
// escaping is needed.
//
// Payload layout (little-endian), mirrored by NativeProcessPayloadCodec.kt:
//
//   byte 0       outcome: 0 EXITED, 1 TERMINATED_BY_SIGNAL, 2 LAUNCH_FAILED,
//                         3 INTERNAL_ERROR
//   u32          stdout byte length; stdout bytes
//   u32          stderr byte length; stderr bytes
//   u8           has_exit (1 only for outcome 0); if 1: i32 exit_code
//   u8           has_signal (1 only for outcome 1); if 1: i32 term_signal
//   u32          error-message byte length; error-message bytes
//
// Every field is length-prefixed so arbitrary captured bytes (embedded newlines,
// NULs, non-UTF-8) round-trip without ambiguity.

#include <jni.h>

#include <cstdint>
#include <errno.h>
#include <string>
#include <vector>

#include "process_execution.h"

namespace {

using aliasnull_runtime::ProcessLaunch;
using aliasnull_runtime::ProcessOutcome;
using aliasnull_runtime::ProcessResult;
using aliasnull_runtime::run_process;

std::string from_jstring(JNIEnv* env, jstring value) {
  const char* utf = env->GetStringUTFChars(value, nullptr);
  if (utf == nullptr) {
    env->ExceptionClear();
    return std::string();
  }
  std::string text(utf);
  env->ReleaseStringUTFChars(value, utf);
  return text;
}

void push_u8(std::vector<uint8_t>& bytes, uint8_t value) { bytes.push_back(value); }

void push_u32(std::vector<uint8_t>& bytes, uint32_t value) {
  bytes.push_back(static_cast<uint8_t>(value & 0xFFu));
  bytes.push_back(static_cast<uint8_t>((value >> 8) & 0xFFu));
  bytes.push_back(static_cast<uint8_t>((value >> 16) & 0xFFu));
  bytes.push_back(static_cast<uint8_t>((value >> 24) & 0xFFu));
}

void push_i32(std::vector<uint8_t>& bytes, int32_t value) {
  push_u32(bytes, static_cast<uint32_t>(value));
}

void push_bytes(std::vector<uint8_t>& bytes, const std::string& text) {
  push_u32(bytes, static_cast<uint32_t>(text.size()));
  bytes.insert(bytes.end(), text.begin(), text.end());
}

std::vector<uint8_t> encode_result(const ProcessResult& result) {
  std::vector<uint8_t> bytes;
  push_u8(bytes, static_cast<uint8_t>(static_cast<int32_t>(result.outcome)));
  push_bytes(bytes, result.stdout_bytes);
  push_bytes(bytes, result.stderr_bytes);
  const bool has_exit = result.outcome == ProcessOutcome::ExitedNormally;
  push_u8(bytes, has_exit ? 1 : 0);
  if (has_exit) push_i32(bytes, result.exit_code);
  const bool has_signal = result.outcome == ProcessOutcome::TerminatedBySignal;
  push_u8(bytes, has_signal ? 1 : 0);
  if (has_signal) push_i32(bytes, result.term_signal);
  push_bytes(bytes, result.error_message);
  return bytes;
}

jbyteArray to_byte_array(JNIEnv* env, const std::vector<uint8_t>& bytes) {
  jbyteArray array = env->NewByteArray(static_cast<jsize>(bytes.size()));
  if (array == nullptr) return nullptr;
  if (!bytes.empty()) {
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte*>(bytes.data()));
  }
  return array;
}

ProcessResult validation_error(const std::string& message) {
  ProcessResult result;
  result.outcome = ProcessOutcome::InternalError;
  result.errno_code = EINVAL;
  result.error_message = message;
  return result;
}

// Converts a Kotlin String[] into a request field, returning false when a null
// element makes it unusable (the caller then returns a structured error).
bool copy_string_array(JNIEnv* env, jobjectArray array,
                       std::vector<std::string>& out) {
  const jsize count = env->GetArrayLength(array);
  for (jsize i = 0; i < count; ++i) {
    jobject element = env->GetObjectArrayElement(array, i);
    if (element == nullptr) return false;
    jstring string = static_cast<jstring>(element);
    const char* utf = env->GetStringUTFChars(string, nullptr);
    if (utf == nullptr) {
      env->ExceptionClear();
      return false;
    }
    out.emplace_back(utf);
    env->ReleaseStringUTFChars(string, utf);
  }
  return true;
}

}  // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_app_aliasnull_shell_runtime_native_NativeRuntimeBridge_nativeRunProcess(
    JNIEnv* env, jobject /*thiz*/, jobjectArray jargv, jobjectArray jenvOverrides,
    jstring jcwd, jbyteArray jstdin) {
  const auto encode_internal = [&](const std::string& message) {
    const ProcessResult error = validation_error(message);
    return to_byte_array(env, encode_result(error));
  };

  try {
    if (jargv == nullptr) return encode_internal("The process request has no argv.");
  if (env->GetArrayLength(jargv) == 0) {
    return encode_internal("The process request has an empty argv.");
  }

  ProcessLaunch launch;
  if (!copy_string_array(env, jargv, launch.argv)) {
    return encode_internal("The process request has a null argv element.");
  }
  if (launch.argv.front().empty()) {
    return encode_internal("The process request has an empty executable name.");
  }

  if (jenvOverrides != nullptr) {
    std::vector<std::string> entries;
    if (!copy_string_array(env, jenvOverrides, entries)) {
      return encode_internal("An environment override element is null.");
    }
    for (const std::string& entry : entries) {
      if (entry.find('=') == std::string::npos || entry[0] == '=') {
        return encode_internal("An environment override is not in KEY=VALUE form.");
      }
      launch.environment_overrides.push_back(entry);
    }
  }

  if (jcwd != nullptr) {
    launch.has_working_directory = true;
    launch.working_directory = from_jstring(env, jcwd);
  }

  if (jstdin != nullptr) {
    launch.has_stdin = true;
    const jsize length = env->GetArrayLength(jstdin);
    if (length > 0) {
      launch.stdin_bytes.resize(static_cast<std::size_t>(length));
      env->GetByteArrayRegion(jstdin, 0, length,
                              reinterpret_cast<jbyte*>(launch.stdin_bytes.data()));
      if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return encode_internal("The process request stdin could not be read.");
      }
    }
  }

  ProcessResult result;
  (void)run_process(launch, result);
  return to_byte_array(env, encode_result(result));
  } catch (...) {
    return encode_internal("The native process runner failed with an internal exception.");
  }
}

}  // extern "C"
