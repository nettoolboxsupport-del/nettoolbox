package de.nettoolbox.vterm

import android.util.Log

/**
 * Safe entry point to the native terminal module - the only thing outside
 * this module should ever call.
 *
 * Every function catches [UnsatisfiedLinkError] and anything the native call
 * raises, matching what `:native:icmp` and `:native:iperf3` already do: a
 * broken native layer must degrade to a reported failure, never take the
 * whole app down.
 */
object VtermBridge {

    private val libraryLoaded: Boolean = runCatching {
        System.loadLibrary("nettoolbox_vterm")
    }.onFailure {
        Log.e("NetToolboxVterm", "Failed to load nettoolbox_vterm native library!", it)
    }.onSuccess {
        Log.i("NetToolboxVterm", "Successfully loaded nettoolbox_vterm native library")
    }.isSuccess

    val isAvailable: Boolean get() = libraryLoaded

    /** Ints a snapshot array needs for a grid of this size. */
    fun snapshotSizeFor(rows: Int, cols: Int): Int = rows * cols * INTS_PER_CELL

    /** @return an opaque terminal handle, or null if the native layer is unusable. */
    fun create(rows: Int, cols: Int): Long? {
        if (!libraryLoaded) {
            Log.e("NetToolboxVterm", "VtermBridge.create failed: library is not loaded!")
            return null
        }
        val ptr = runCatching { nativeNew(rows, cols) }
            .onFailure { Log.e("NetToolboxVterm", "nativeNew threw exception: ${it.message}", it) }
            .getOrNull()?.takeIf { it != 0L }
        Log.d("NetToolboxVterm", "VtermBridge.create(${rows}x${cols}) returned ptr=$ptr")
        return ptr
    }

    fun free(ptr: Long) {
        if (!libraryLoaded) return
        runCatching { nativeFree(ptr) }
    }

    fun setSize(ptr: Long, rows: Int, cols: Int) {
        if (!libraryLoaded) return
        runCatching { nativeSetSize(ptr, rows, cols) }
    }

    fun getRows(ptr: Long): Int {
        if (!libraryLoaded) return 0
        return runCatching { nativeGetRows(ptr) }.getOrDefault(0)
    }

    fun getCols(ptr: Long): Int {
        if (!libraryLoaded) return 0
        return runCatching { nativeGetCols(ptr) }.getOrDefault(0)
    }

    fun write(ptr: Long, data: ByteArray, len: Int = data.size) {
        if (!libraryLoaded) return
        runCatching { nativeWrite(ptr, data, len) }
    }

    fun isDirty(ptr: Long): Boolean {
        if (!libraryLoaded) return false
        return runCatching { nativeIsDirty(ptr) }.getOrDefault(false)
    }

    /** @return the number of cells written, or null on any failure. */
    fun snapshot(ptr: Long, out: IntArray): Int? {
        if (!libraryLoaded) return null
        return runCatching { nativeSnapshot(ptr, out) }.getOrNull()?.takeIf { it >= 0 }
    }

    /** @return the cursor position, or null if it could not be read. */
    fun cursor(ptr: Long): VtermCursor? {
        if (!libraryLoaded) return null
        val packed = runCatching { nativeCursor(ptr) }.getOrNull() ?: return null
        if (packed < 0) return null
        return VtermCursor(
            row = ((packed shr 16) and 0xFFFFL).toInt(),
            col = (packed and 0xFFFFL).toInt(),
            visible = ((packed shr 32) and 1L) == 1L,
        )
    }

    fun keyUnichar(ptr: Long, codePoint: Int, modifiers: Int = VtermModifier.NONE) {
        if (!libraryLoaded) return
        runCatching { nativeKeyUnichar(ptr, codePoint, modifiers) }
    }

    fun keyKey(ptr: Long, key: Int, modifiers: Int = VtermModifier.NONE) {
        if (!libraryLoaded) return
        runCatching { nativeKeyKey(ptr, key, modifiers) }
    }

    /** @return the number of bytes placed in [dest], or 0 on failure. */
    fun readOutput(ptr: Long, dest: ByteArray): Int {
        if (!libraryLoaded) return 0
        return runCatching { nativeReadOutput(ptr, dest) }.getOrDefault(0).coerceAtLeast(0)
    }

    /** Diagnostic: keyboard bytes lost because the native buffer filled up. */
    fun droppedOutputBytes(ptr: Long): Long {
        if (!libraryLoaded) return 0
        return runCatching { nativeDroppedOutputBytes(ptr) }.getOrDefault(0L)
    }
}

data class VtermCursor(
    val row: Int,
    val col: Int,
    val visible: Boolean,
)

/**
 * One decoded cell from a snapshot array.
 *
 * Reading is deliberately offered as index arithmetic over the raw IntArray
 * rather than as a list of objects: a full-screen redraw allocating one
 * object per cell is exactly the kind of per-frame garbage that makes a
 * terminal stutter.
 */
object VtermCell {
    const val ATTR_BOLD: Int = 1 shl 0
    const val ATTR_ITALIC: Int = 1 shl 1
    const val ATTR_BLINK: Int = 1 shl 2
    const val ATTR_REVERSE: Int = 1 shl 3
    const val ATTR_STRIKE: Int = 1 shl 4
    const val ATTR_CONCEAL: Int = 1 shl 5

    const val UNDERLINE_OFF: Int = 0
    const val UNDERLINE_SINGLE: Int = 1
    const val UNDERLINE_DOUBLE: Int = 2
    const val UNDERLINE_CURLY: Int = 3

    fun codePoint(grid: IntArray, index: Int): Int = grid[index * INTS_PER_CELL]

    fun foregroundRgb(grid: IntArray, index: Int): Int = grid[index * INTS_PER_CELL + 1]

    fun backgroundRgb(grid: IntArray, index: Int): Int = grid[index * INTS_PER_CELL + 2]

    fun attributes(grid: IntArray, index: Int): Int = grid[index * INTS_PER_CELL + 3]

    fun hasAttribute(grid: IntArray, index: Int, mask: Int): Boolean =
        (attributes(grid, index) and mask) != 0

    fun underline(grid: IntArray, index: Int): Int =
        (attributes(grid, index) shr 6) and 0x3

    /**
     * Cell width: 2 for a double-width glyph (CJK, many emoji), 1 otherwise.
     *
     * The cell following a double-width one carries no character of its own
     * and must be skipped by the renderer, not drawn as a blank.
     */
    fun width(grid: IntArray, index: Int): Int =
        ((attributes(grid, index) shr 16) and 0x3).coerceAtLeast(1)
}
