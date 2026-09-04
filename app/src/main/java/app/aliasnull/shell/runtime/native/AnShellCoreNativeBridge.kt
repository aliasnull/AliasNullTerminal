package app.aliasnull.shell.runtime.native

import android.util.Log

/**
 * The single owner of the Kotlin <-> native boundary for
 * libaliasnull_an_shell_core.so.
 *
 * Every [System.loadLibrary] call and every JNI entry point for the AN Shell
 * core is confined to this object, mirroring how [NativeRuntimeBridge] owns the
 * separate libaliasnull_runtime.so. Loading happens only when the AN Shell core
 * bridge is first verified (see [AnShellCoreBridge.verify]), never eagerly, and
 * only this object ever loads this library, so the .so can never be loaded twice
 * from different places. Nothing above the runtime layer references this object
 * or a JNI symbol directly.
 *
 * The external declarations map to the mangled symbols implemented in
 * rust/aliasnull_an_shell_core/src/ffi.rs for the Kotlin object
 * app.aliasnull.shell.runtime.native.AnShellCoreNativeBridge; keep names and
 * signatures in sync with that file. This object is a singleton, so its native
 * methods are instance methods (the native second parameter is a jobject). The
 * two symbols are case/underscore free, so the JNI names need no `_1` escaping.
 */
internal object AnShellCoreNativeBridge {

    private const val TAG = "AnShellCoreNativeBridge"
    private const val LIBRARY_NAME = "aliasnull_an_shell_core"

    @Volatile
    private var libraryLoaded = false

    private val lock = Any()

    val isLibraryLoaded: Boolean
        get() = libraryLoaded

    /**
     * Loads libaliasnull_an_shell_core.so exactly once. Safe to call repeatedly
     * and from any thread; returns false (never throws) when the library cannot
     * be loaded, so an unsupported ABI or a missing .so degrades to an honest
     * error state instead of a crash.
     */
    fun ensureLibraryLoaded(): Boolean {
        if (libraryLoaded) return true
        synchronized(lock) {
            if (libraryLoaded) return true
            Log.i(TAG, "Native library load started: aliasnull_an_shell_core")
            libraryLoaded = try {
                System.loadLibrary(LIBRARY_NAME)
                Log.i(TAG, "libaliasnull_an_shell_core.so loaded successfully")
                true
            } catch (t: UnsatisfiedLinkError) {
                Log.e(TAG, "Native library load failed (library missing or ABI unsupported)", t)
                false
            } catch (t: SecurityException) {
                Log.e(TAG, "Native library load blocked", t)
                false
            }
        }
        return libraryLoaded
    }

    /**
     * Reads the native core's API version constant, or null when the handshake
     * could not run (library not loaded, or the symbol could not be bound on a
     * stale .so). The value must equal [AnShellCoreBridge]'s expected version.
     */
    fun readApiVersion(): Int? {
        if (!ensureLibraryLoaded()) return null
        return try {
            nativeApiVersion()
        } catch (t: Throwable) {
            Log.e(TAG, "nativeApiVersion could not be called (library out of sync?)", t)
            null
        }
    }

    /**
     * Sends one command's UTF-8 bytes to the native core and returns the encoded
     * result payload.
     *
     * Returns null only when the JNI boundary itself could not read the input or
     * build the output array -- an exceptional, non-command condition that the
     * caller maps to a structured internal result. Every command outcome
     * (success and every lexer/parser/semantic/internal error) is a valid,
     * non-null payload.
     */
    fun executeCommandBytes(command: ByteArray): ByteArray? {
        if (!ensureLibraryLoaded()) return null
        return try {
            nativeExecuteCommand(command)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeExecuteCommand could not run", t)
            null
        }
    }

    // ---- JNI entry points (implemented in libaliasnull_an_shell_core.so) ----

    private external fun nativeApiVersion(): Int

    private external fun nativeExecuteCommand(command: ByteArray): ByteArray?
}
