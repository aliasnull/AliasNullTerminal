//! The Part 27-G JNI boundary: the tiny, unsafe-free surface Kotlin loads and
//! calls on `libaliasnull_an_shell_core.so`.
//!
//! Two `#[no_mangle]` functions are exported under the JNI name-mangling scheme
//! for the Kotlin object `app.aliasnull.shell.runtime.native.AnShellCoreNativeBridge`:
//!
//! * `nativeApiVersion`       - instance method returning `jint`; delegates to
//!   the unchanged Part 27-B `aliasnull_an_shell_core_api_version()` identity
//!   function so there is a single source of truth for the ABI version.
//! * `nativeExecuteCommand`   - instance method taking the command's UTF-8
//!   bytes as a `byte[]` and returning the encoded result payload as a `byte[]`
//!   (layout owned by `crate::bridge`). `null` is returned only when the JNI
//!   layer itself could not read the input or build the output array (an
//!   exceptional, non-command condition); Kotlin maps `null` to a structured
//!   internal/unavailable result.
//!
//! Ownership & safety
//! ------------------
//! This module is deliberately tiny and contains **no `unsafe` block**. All real
//! work happens in safe Rust: the JNIEnv byte-array helpers in the `jni` crate
//! are safe wrappers, `crate::bridge::run_command` is total and panic-free over
//! any input, and `crate::bridge::encode_outcome` is panic-free. The body is
//! additionally wrapped in `std::panic::catch_unwind` as containment defence in
//! depth; the functions are written not to panic regardless. No process, PTY,
//! filesystem, environment or Linux-runtime behaviour exists anywhere behind
//! this boundary.
//!
//! No Rust type is ever exposed across the ABI: only `jint` and raw `byte[]`
//! cross the boundary, and every allocation is owned by the JVM (a JNI local
//! reference to a `byte[]`), so there is no Rust handle to leak and no
//! use-after-free or double-free surface. The two Java symbols are case and
//! underscore free, so the JNI mangled names need no `_1`/`_2` escaping and are
//! written verbatim below.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;

use crate::bridge::{encode_outcome, run_command, AnShellCoreOutcome};

/// JNI wrapper over the unchanged identity function, so the Kotlin handshake and
/// the crate's ABI constant can never drift apart. `0x0000_0100` for 0.1.0.
#[no_mangle]
pub extern "system" fn Java_app_aliasnull_shell_runtime_native_AnShellCoreNativeBridge_nativeApiVersion<
    'local,
>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jint {
    crate::aliasnull_an_shell_core_api_version() as jint
}

/// Executes one command line and returns the encoded result payload.
///
/// Returns `null` only when the JNI layer could not obtain the input bytes or
/// could not allocate the output byte array -- exceptional conditions that are
/// not command failures. Every command outcome (success, lexer/parser/semantic
/// error, internal error) is encoded into a valid payload and returned.
#[no_mangle]
pub extern "system" fn Java_app_aliasnull_shell_runtime_native_AnShellCoreNativeBridge_nativeExecuteCommand<
    'local,
>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    command: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| execute_command(&env, command))) {
        Ok(Some(array)) => array,
        Ok(None) | Err(_) => ptr::null_mut(),
    }
}

/// Reads the command bytes, runs the real pipeline, and builds the output array.
///
/// Returns `None` (mapped to a Java `null` byte[]) only on a JNIEnv failure;
/// invalid UTF-8 input is converted into a structured `InternalError` outcome,
/// never a panic.
fn execute_command(env: &JNIEnv<'_>, command: JByteArray<'_>) -> Option<jbyteArray> {
    let input: Vec<u8> = env.convert_byte_array(command).ok()?;

    let outcome = match String::from_utf8(input) {
        Ok(text) => run_command(&text),
        Err(_) => AnShellCoreOutcome::InternalError {
            message: "The command is not valid UTF-8; execution did not run.".to_owned(),
        },
    };

    let payload = encode_outcome(&outcome);
    let array = env.byte_array_from_slice(&payload).ok()?;
    Some(array.into_raw() as jbyteArray)
}
