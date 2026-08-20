/*
 * JNI bridge to the vendored libvterm (MIT, see libvterm-src/LICENSE).
 *
 * Scope note: this drives a *remote* terminal. libvterm is a pure state
 * machine - it turns a byte stream into a screen grid, and turns key presses
 * back into bytes. It never touches a pty, never forks, never execs. All of
 * that belongs to the SSH layer, which is exactly why none of Termux's
 * local-process machinery is needed here, and its GPLv3 with it.
 *
 * Two rules shape this file:
 *
 * 1. No per-cell JNI calls. Crossing the JNI boundary once per character
 *    would be ruinous at 80x24, let alone larger. nativeSnapshot fills one
 *    caller-owned int array in a single call.
 * 2. Nothing here may take the process down. Every entry point checks its
 *    handle, and every array write is bounded by the array's real length
 *    rather than by what the caller claims.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "vterm.h"

/* Bytes libvterm may hand back from key presses before we drain them. The
 * keyboard produces very little - a few bytes per key, a paste being the
 * outlier - so this is generous. Overflow is dropped rather than grown:
 * silently reallocating inside a callback that has no way to report failure
 * is worse than losing input we can at least count. */
#define OUT_BUF_SIZE 8192

/* Ints written per cell by nativeSnapshot. Mirrored in VtermNative.kt - the
 * two must be changed together. */
#define INTS_PER_CELL 4

typedef struct {
    VTerm *vt;
    VTermScreen *screen;
    VTermState *state;

    char out[OUT_BUF_SIZE];
    size_t out_len;
    size_t out_dropped;

    int dirty;
    int cursor_visible;
} nettoolbox_term;

/* ---------- libvterm callbacks ---------- */

static void output_cb(const char *s, size_t len, void *user) {
    nettoolbox_term *t = (nettoolbox_term *) user;
    if (t == NULL) return;

    size_t space = OUT_BUF_SIZE - t->out_len;
    if (len > space) {
        t->out_dropped += (len - space);
        len = space;
    }
    if (len > 0) {
        memcpy(t->out + t->out_len, s, len);
        t->out_len += len;
    }
}

/* All the screen callbacks do here is mark the grid as changed. Fine-grained
 * damage rectangles are deliberately ignored for now: the renderer reads the
 * whole grid anyway, and a wrong "nothing changed" is a far nastier bug than
 * a redundant redraw. Returning 1 tells libvterm the callback was handled. */
static int damage_cb(VTermRect rect, void *user) {
    ((nettoolbox_term *) user)->dirty = 1;
    return 1;
}

static int moverect_cb(VTermRect dest, VTermRect src, void *user) {
    ((nettoolbox_term *) user)->dirty = 1;
    return 1;
}

static int movecursor_cb(VTermPos pos, VTermPos oldpos, int visible, void *user) {
    ((nettoolbox_term *) user)->dirty = 1;
    return 1;
}

static int settermprop_cb(VTermProp prop, VTermValue *val, void *user) {
    nettoolbox_term *t = (nettoolbox_term *) user;
    if (prop == VTERM_PROP_CURSORVISIBLE && val != NULL) {
        t->cursor_visible = val->boolean;
    }
    t->dirty = 1;
    /* Returning 0 for props we do not handle lets libvterm apply its own
     * default behaviour rather than believing we dealt with it. */
    return prop == VTERM_PROP_CURSORVISIBLE;
}

/* Designated initialisers on purpose: VTermScreenCallbacks has grown fields
 * across libvterm versions, and positional initialisation would silently
 * attach the wrong function to the wrong slot after an upgrade. */
static const VTermScreenCallbacks screen_callbacks = {
    .damage      = damage_cb,
    .moverect    = moverect_cb,
    .movecursor  = movecursor_cb,
    .settermprop = settermprop_cb,
};

/* ---------- lifecycle ---------- */

JNIEXPORT jlong JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeNew(JNIEnv *env, jclass clazz, jint rows, jint cols) {
    if (rows <= 0 || cols <= 0) return 0;

    nettoolbox_term *t = (nettoolbox_term *) calloc(1, sizeof(nettoolbox_term));
    if (t == NULL) return 0;

    t->vt = vterm_new(rows, cols);
    if (t->vt == NULL) {
        free(t);
        return 0;
    }

    vterm_set_utf8(t->vt, 1);
    vterm_output_set_callback(t->vt, output_cb, t);

    t->state = vterm_obtain_state(t->vt);
    t->screen = vterm_obtain_screen(t->vt);
    vterm_screen_set_callbacks(t->screen, &screen_callbacks, t);
    vterm_screen_enable_altscreen(t->screen, 1);

    /* Required before the screen is usable: this is what allocates the cell
     * buffers. A hard reset (1) rather than soft, since nothing has run yet. */
    vterm_screen_reset(t->screen, 1);

    t->cursor_visible = 1;
    t->dirty = 1;

    return (jlong) (intptr_t) t;
}

JNIEXPORT void JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeFree(JNIEnv *env, jclass clazz, jlong ptr) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL) return;
    if (t->vt != NULL) vterm_free(t->vt);
    free(t);
}

JNIEXPORT void JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeSetSize(JNIEnv *env, jclass clazz,
                                                   jlong ptr, jint rows, jint cols) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL || t->vt == NULL || rows <= 0 || cols <= 0) return;
    vterm_set_size(t->vt, rows, cols);
    t->dirty = 1;
}

JNIEXPORT jint JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeGetRows(JNIEnv *env, jclass clazz, jlong ptr) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL || t->vt == NULL) return 0;
    int rows = 0, cols = 0;
    vterm_get_size(t->vt, &rows, &cols);
    return (jint) rows;
}

JNIEXPORT jint JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeGetCols(JNIEnv *env, jclass clazz, jlong ptr) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL || t->vt == NULL) return 0;
    int rows = 0, cols = 0;
    vterm_get_size(t->vt, &rows, &cols);
    return (jint) cols;
}

/* ---------- feeding remote output in ---------- */

JNIEXPORT void JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeWrite(JNIEnv *env, jclass clazz,
                                                 jlong ptr, jbyteArray data, jint len) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL || t->vt == NULL || data == NULL || len <= 0) return;

    /* Trust the array, not the caller's length. */
    jsize avail = (*env)->GetArrayLength(env, data);
    if (len > avail) len = avail;

    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return;

    vterm_input_write(t->vt, (const char *) bytes, (size_t) len);

    /* JNI_ABORT: we did not modify the buffer, so there is nothing to copy
     * back. This is the cheap release mode. */
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

/* ---------- reading the grid out ---------- */

JNIEXPORT jboolean JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeIsDirty(JNIEnv *env, jclass clazz, jlong ptr) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL) return JNI_FALSE;
    return t->dirty ? JNI_TRUE : JNI_FALSE;
}

/*
 * Packs the whole visible grid into `out`, four ints per cell:
 *   [0] Unicode code point (0 = blank)
 *   [1] foreground as 0xRRGGBB
 *   [2] background as 0xRRGGBB
 *   [3] attribute bits, plus cell width in bits 16-17
 *
 * Returns the number of cells written, or -1 if the array is too small.
 */
JNIEXPORT jint JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeSnapshot(JNIEnv *env, jclass clazz,
                                                    jlong ptr, jintArray out) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL || t->vt == NULL || t->screen == NULL || out == NULL) return -1;

    int rows = 0, cols = 0;
    vterm_get_size(t->vt, &rows, &cols);
    if (rows <= 0 || cols <= 0) return -1;

    jsize capacity = (*env)->GetArrayLength(env, out);
    if (capacity < INTS_PER_CELL) return -1;

    jint *buf = (*env)->GetIntArrayElements(env, out, NULL);
    if (buf == NULL) return -1;

    long max_cells = (long) (capacity / INTS_PER_CELL);
    long total_cells = (long) rows * (long) cols;
    long cells_to_write = (total_cells < max_cells) ? total_cells : max_cells;

    long i = 0;
    long cell_count = 0;
    for (int row = 0; row < rows && cell_count < cells_to_write; row++) {
        for (int col = 0; col < cols && cell_count < cells_to_write; col++) {
            VTermPos pos;
            pos.row = row;
            pos.col = col;

            VTermScreenCell cell;
            memset(&cell, 0, sizeof(cell));

            if (!vterm_screen_get_cell(t->screen, pos, &cell)) {
                /* Unreadable cell: emit a blank rather than leaving stale
                 * memory in the buffer. */
                buf[i++] = 0;
                buf[i++] = 0x00FFFFFF;
                buf[i++] = 0x00000000;
                buf[i++] = 1 << 16;
                cell_count++;
                continue;
            }

            /* chars[0] only. Combining marks beyond the first are dropped - a
             * documented limitation, not an oversight: carrying all six would
             * quadruple the packing, and the renderer draws one code point
             * per cell anyway. */
            buf[i++] = (jint) cell.chars[0];

            VTermColor fg = cell.fg;
            VTermColor bg = cell.bg;
            /* Resolves palette indices and the "default colour" flags into
             * concrete RGB, so Kotlin never has to know about the palette. */
            vterm_screen_convert_color_to_rgb(t->screen, &fg);
            vterm_screen_convert_color_to_rgb(t->screen, &bg);

            buf[i++] = (jint) ((fg.rgb.red << 16) | (fg.rgb.green << 8) | fg.rgb.blue);
            buf[i++] = (jint) ((bg.rgb.red << 16) | (bg.rgb.green << 8) | bg.rgb.blue);

            jint attrs = 0;
            if (cell.attrs.bold)    attrs |= 1 << 0;
            if (cell.attrs.italic)  attrs |= 1 << 1;
            if (cell.attrs.blink)   attrs |= 1 << 2;
            if (cell.attrs.reverse) attrs |= 1 << 3;
            if (cell.attrs.strike)  attrs |= 1 << 4;
            if (cell.attrs.conceal) attrs |= 1 << 5;
            attrs |= (jint) (cell.attrs.underline & 0x3) << 6;
            attrs |= (jint) (cell.width & 0x3) << 16;
            buf[i++] = attrs;
            cell_count++;
        }
    }

    (*env)->ReleaseIntArrayElements(env, out, buf, 0);
    t->dirty = 0;
    return (jint) cell_count;
}

/* Returns row in bits 16-31, column in bits 0-15, visibility in bit 32. */
JNIEXPORT jlong JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeCursor(JNIEnv *env, jclass clazz, jlong ptr) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL || t->state == NULL) return -1;

    VTermPos pos;
    pos.row = 0;
    pos.col = 0;
    vterm_state_get_cursorpos(t->state, &pos);

    jlong packed = ((jlong) (pos.row & 0xFFFF) << 16) | (jlong) (pos.col & 0xFFFF);
    if (t->cursor_visible) packed |= (jlong) 1 << 32;
    return packed;
}

/* ---------- keyboard: producing bytes to send back ---------- */

JNIEXPORT void JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeKeyUnichar(JNIEnv *env, jclass clazz,
                                                      jlong ptr, jint codePoint, jint modifiers) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL || t->vt == NULL) return;
    vterm_keyboard_unichar(t->vt, (uint32_t) codePoint, (VTermModifier) modifiers);
}

JNIEXPORT void JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeKeyKey(JNIEnv *env, jclass clazz,
                                                  jlong ptr, jint key, jint modifiers) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL || t->vt == NULL) return;
    vterm_keyboard_key(t->vt, (VTermKey) key, (VTermModifier) modifiers);
}

/*
 * Drains bytes libvterm produced from key presses, to be written to the SSH
 * channel.
 *
 * Returns the number of bytes copied into `dest`, or -1 on a bad handle.
 */
JNIEXPORT jint JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeReadOutput(JNIEnv *env, jclass clazz,
                                                      jlong ptr, jbyteArray dest) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL || dest == NULL) return -1;
    if (t->out_len == 0) return 0;

    jsize capacity = (*env)->GetArrayLength(env, dest);
    size_t n = t->out_len;
    if (n > (size_t) capacity) n = (size_t) capacity;

    (*env)->SetByteArrayRegion(env, dest, 0, (jsize) n, (const jbyte *) t->out);

    /* Keep whatever did not fit; the caller will come back for it. */
    if (n < t->out_len) {
        memmove(t->out, t->out + n, t->out_len - n);
        t->out_len -= n;
    } else {
        t->out_len = 0;
    }
    return (jint) n;
}

/* Diagnostic only: how many keyboard bytes were lost to a full buffer. A
 * non-zero value means the drain loop is not keeping up, which is worth
 * seeing rather than guessing at. */
JNIEXPORT jlong JNICALL
Java_de_nettoolbox_vterm_VtermNative_nativeDroppedOutputBytes(JNIEnv *env, jclass clazz, jlong ptr) {
    nettoolbox_term *t = (nettoolbox_term *) (intptr_t) ptr;
    if (t == NULL) return 0;
    return (jlong) t->out_dropped;
}
