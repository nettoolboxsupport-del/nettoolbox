package de.nettoolbox.core.terminal

import de.nettoolbox.vterm.VtermBridge
import de.nettoolbox.vterm.VtermModifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One libvterm screen plus the keyboard state that goes with it.
 *
 * It turns keystrokes into the bytes a terminal would send - arrow keys into
 * escape sequences, Ctrl+C into 0x03 - and turns received bytes into what is on
 * screen. Where those bytes travel is the caller's business.
 *
 * ### Threading: everything on the main thread
 *
 * libvterm is not thread-safe, and the renderer reads the screen from the main
 * thread during drawing. Feeding it from an I/O thread at the same time is a
 * data race on native memory. So every call here, [feed] included, is made on
 * the main thread; a reader loop reads on its I/O thread and hands the bytes
 * over. At serial line rates - 115200 baud is 11.5 KB/s - the cost of that hop
 * is nothing.
 *
 * The SSH terminal predates this class and still drives VtermBridge directly.
 * It has not been migrated on purpose: its session code was the subject of a
 * long debugging effort and works, and moving it for tidiness alone would put
 * that at risk for no user-visible gain.
 */
class TerminalEmulator(initialRows: Int = 24, initialCols: Int = 80) {

    private var ptr: Long? = VtermBridge.create(initialRows, initialCols)

    /** The native handle for [TerminalPane], or null if the native layer failed to load. */
    val handle: Long? get() = ptr

    val isAvailable: Boolean get() = ptr != null

    var rows: Int = initialRows
        private set
    var cols: Int = initialCols
        private set

    private val _redrawTrigger = MutableStateFlow(0L)

    /** Bumped whenever the screen changed; the canvas redraws on it. */
    val redrawTrigger: StateFlow<Long> = _redrawTrigger.asStateFlow()

    private val _ctrlActive = MutableStateFlow(false)
    val ctrlActive: StateFlow<Boolean> = _ctrlActive.asStateFlow()

    private val _altActive = MutableStateFlow(false)
    val altActive: StateFlow<Boolean> = _altActive.asStateFlow()

    private val outputBuffer = ByteArray(OUTPUT_BUFFER_SIZE)

    fun toggleCtrl() = _ctrlActive.update { !it }

    fun toggleAlt() = _altActive.update { !it }

    /** Received bytes onto the screen. Main thread only. */
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        val handle = ptr ?: return
        if (length <= 0) return
        VtermBridge.write(handle, bytes, length)
        _redrawTrigger.update { it + 1 }
    }

    /**
     * A typed character, returned as the bytes to transmit.
     *
     * The latching Ctrl and Alt keys of the extra-key bar are applied and then
     * released: they are one-shot, like a phone's shift key, because a Ctrl that
     * stays down after Ctrl+C is how a user ends up sending Ctrl+L on the next
     * letter without noticing.
     */
    fun typeChar(char: Char): ByteArray {
        val handle = ptr ?: return EMPTY
        VtermBridge.keyUnichar(handle, char.code, consumeModifiers())
        return drainOutput(handle)
    }

    /** A special key (arrows, Enter, F1 ...), returned as the bytes to transmit. */
    fun typeKey(key: Int): ByteArray {
        val handle = ptr ?: return EMPTY
        VtermBridge.keyKey(handle, key, consumeModifiers())
        return drainOutput(handle)
    }

    /** @return true when the size actually changed. */
    fun resize(newRows: Int, newCols: Int): Boolean {
        if (newRows <= 0 || newCols <= 0) return false
        if (newRows == rows && newCols == cols) return false
        rows = newRows
        cols = newCols
        ptr?.let { VtermBridge.setSize(it, newRows, newCols) }
        _redrawTrigger.update { it + 1 }
        return true
    }

    /**
     * Clears screen and scrollback by feeding the standard erase sequences.
     *
     * Not by freeing and recreating the native terminal: the renderer holds the
     * pointer as a plain value, and a frame drawn in between would read freed
     * memory. That exact bug existed in the SSH terminal once.
     */
    fun clear() = feed(CLEAR_SEQUENCE)

    fun release() {
        ptr?.let { VtermBridge.free(it) }
        ptr = null
    }

    private fun consumeModifiers(): Int {
        var modifiers = VtermModifier.NONE
        if (_ctrlActive.value) modifiers = modifiers or VtermModifier.CTRL
        if (_altActive.value) modifiers = modifiers or VtermModifier.ALT
        _ctrlActive.value = false
        _altActive.value = false
        return modifiers
    }

    private fun drainOutput(handle: Long): ByteArray {
        val count = VtermBridge.readOutput(handle, outputBuffer)
        _redrawTrigger.update { it + 1 }
        return if (count > 0) outputBuffer.copyOf(count) else EMPTY
    }

    private companion object {
        const val OUTPUT_BUFFER_SIZE = 512
        val EMPTY = ByteArray(0)

        /* ESC [ 2 J, ESC [ 3 J, ESC [ H - as bytes, not as a string with
         * escapes: such a string became a raw control character in a source
         * file in this project once. */
        val CLEAR_SEQUENCE: ByteArray = byteArrayOf(
            0x1B, 0x5B, 0x32, 0x4A,
            0x1B, 0x5B, 0x33, 0x4A,
            0x1B, 0x5B, 0x48,
        )
    }
}
