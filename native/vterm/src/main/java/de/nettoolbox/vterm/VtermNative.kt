@file:JvmName("VtermNative")

package de.nettoolbox.vterm

/**
 * Raw JNI bridge to vterm_jni.c, over the vendored libvterm.
 *
 * `@file:JvmName("VtermNative")` pins the generated class name, which pins
 * the JNI symbol names in turn - see IcmpNative.kt in `:native:icmp` for the
 * same reasoning.
 *
 * Nothing here is safe to call directly: every function takes a raw pointer
 * and none of them validate it beyond a null check on the C side. [VtermBridge]
 * is the guarded entry point; use that instead.
 */

/** @return an opaque terminal handle, or 0 on failure. */
external fun nativeNew(rows: Int, cols: Int): Long

external fun nativeFree(ptr: Long)

external fun nativeSetSize(ptr: Long, rows: Int, cols: Int)

external fun nativeGetRows(ptr: Long): Int

external fun nativeGetCols(ptr: Long): Int

/** Feeds bytes received from the remote host into the parser. */
external fun nativeWrite(ptr: Long, data: ByteArray, len: Int)

/** True when the grid has changed since the last [nativeSnapshot]. */
external fun nativeIsDirty(ptr: Long): Boolean

/**
 * Packs the whole visible grid into [out], [INTS_PER_CELL] ints per cell.
 *
 * @return the number of cells written, or -1 if [out] is too small.
 */
external fun nativeSnapshot(ptr: Long, out: IntArray): Int

/** @return row in bits 16-31, column in bits 0-15, visibility in bit 32; -1 on a bad handle. */
external fun nativeCursor(ptr: Long): Long

external fun nativeKeyUnichar(ptr: Long, codePoint: Int, modifiers: Int)

external fun nativeKeyKey(ptr: Long, key: Int, modifiers: Int)

/** @return the number of bytes written into [dest], or -1 on a bad handle. */
external fun nativeReadOutput(ptr: Long, dest: ByteArray): Int

external fun nativeDroppedOutputBytes(ptr: Long): Long

/**
 * Ints per cell in the snapshot array.
 *
 * Mirrors `INTS_PER_CELL` in vterm_jni.c - the two must be changed together,
 * and [VtermBridge.snapshotSizeFor] is the only place that should compute an
 * array size from it.
 */
const val INTS_PER_CELL: Int = 4

/**
 * Key modifiers, mirroring `VTermModifier` in libvterm-src/include/vterm_keycodes.h.
 *
 * Values are read from that header, not assumed: libvterm uses explicit
 * hex constants here rather than a plain sequence.
 */
object VtermModifier {
    const val NONE: Int = 0x00
    const val SHIFT: Int = 0x01
    const val ALT: Int = 0x02
    const val CTRL: Int = 0x04
}

/**
 * Non-printing keys, mirroring `VTermKey` in libvterm-src/include/vterm_keycodes.h.
 *
 * These are the enum's ordinal values. The sequence is unbroken from NONE to
 * PAGEDOWN, then jumps: libvterm sets `VTERM_KEY_FUNCTION_0 = 256` explicitly
 * and reserves 256 slots for function keys, so the keypad entries start at
 * 512 rather than continuing from 14. Getting that jump wrong would silently
 * send the wrong escape sequence, which is why [function] computes F-keys
 * rather than listing them.
 */
object VtermKey {
    const val NONE: Int = 0
    const val ENTER: Int = 1
    const val TAB: Int = 2
    const val BACKSPACE: Int = 3
    const val ESCAPE: Int = 4
    const val UP: Int = 5
    const val DOWN: Int = 6
    const val LEFT: Int = 7
    const val RIGHT: Int = 8
    const val INS: Int = 9
    const val DEL: Int = 10
    const val HOME: Int = 11
    const val END: Int = 12
    const val PAGEUP: Int = 13
    const val PAGEDOWN: Int = 14

    /** `VTERM_KEY_FUNCTION_0`, given an explicit value of 256 in the header. */
    const val FUNCTION_0: Int = 256

    /** `VTERM_KEY_FUNCTION_MAX` = FUNCTION_0 + 255. */
    const val FUNCTION_MAX: Int = FUNCTION_0 + 255

    /** F-keys are computed, matching the header's `VTERM_KEY_FUNCTION(n)` macro. */
    fun function(n: Int): Int = FUNCTION_0 + n
}
