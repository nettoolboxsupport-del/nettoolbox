package de.nettoolbox.core.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import de.nettoolbox.vterm.VtermKey

/**
 * A complete terminal: screen, keyboard capture and extra-key bar.
 *
 * Transport-agnostic on purpose. It knows nothing about SSH or serial lines;
 * keystrokes leave through [onChar] and [onKey], and whatever arrives from the
 * other end is fed into the emulator behind [vtermPtr] by the caller.
 *
 * ### How keyboard input is caught
 *
 * Compose has no "terminal" input type, and the soft keyboard only talks to
 * something that holds focus and accepts text. The screen therefore carries a
 * one-pixel, almost transparent [BasicTextField] that takes the focus. Every
 * character typed into it is forwarded and the field is reset at once, so it
 * never holds more than a single placeholder character.
 *
 * That placeholder is what makes Backspace work. An earlier version emptied the
 * field completely, and the soft keyboard's Backspace then asked an empty field
 * to delete the character before the cursor: nothing changed, nothing was
 * reported, and the key did nothing. Found on the first hardware test, at an
 * Aruba CX console. With one character always present, a deletion shows up as
 * the text getting shorter and is sent as Backspace.
 *
 * Hardware keys - Enter, arrows, Escape, from a Bluetooth or USB keyboard - do
 * not produce text at all and are caught separately through [onKeyEvent].
 *
 * This arrangement came out of the SSH terminal's "cannot type" investigation
 * and was moved here unchanged rather than rewritten.
 *
 * The caller is expected to place this in a Column with `imePadding()`, so the
 * soft keyboard shrinks the terminal instead of covering its last lines - which
 * are the ones being typed on.
 */
@Composable
fun TerminalPane(
    vtermPtr: Long?,
    redrawTrigger: Long,
    ctrlActive: Boolean,
    altActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onChar: (Char) -> Unit,
    onKey: (Int) -> Unit,
    onResize: (rows: Int, cols: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var rawInputText by remember { mutableStateOf(emptyInput()) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            TerminalCanvas(
                vtermPtr = vtermPtr,
                redrawTrigger = redrawTrigger,
                onResize = onResize,
                onTap = {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
            )

            BasicTextField(
                value = rawInputText,
                onValueChange = { newVal ->
                    val text = newVal.text
                    if (text.length < PLACEHOLDER.length) {
                        // The placeholder was deleted: that is a Backspace.
                        // Holding the key down arrives as repeated deletions,
                        // each handled here in turn.
                        onKey(VtermKey.BACKSPACE)
                    } else {
                        // Whatever follows the placeholder was typed. If the
                        // keyboard replaced the placeholder instead of typing
                        // after it, the whole text is new input.
                        val typed = if (text.startsWith(PLACEHOLDER)) {
                            text.substring(PLACEHOLDER.length)
                        } else {
                            text
                        }
                        for (c in typed) {
                            if (c == '\n' || c == '\r') onKey(VtermKey.ENTER) else onChar(c)
                        }
                    }
                    // Always back to exactly one placeholder with the cursor
                    // behind it, so the next Backspace has something to delete.
                    rawInputText = emptyInput()
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0.01f)
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val key = when (event.key) {
                            Key.Enter -> VtermKey.ENTER
                            Key.Backspace -> VtermKey.BACKSPACE
                            Key.Tab -> VtermKey.TAB
                            Key.Escape -> VtermKey.ESCAPE
                            Key.DirectionUp -> VtermKey.UP
                            Key.DirectionDown -> VtermKey.DOWN
                            Key.DirectionLeft -> VtermKey.LEFT
                            Key.DirectionRight -> VtermKey.RIGHT
                            else -> null
                        }
                        if (key != null) onKey(key)
                        key != null
                    },
                textStyle = TextStyle(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onKey(VtermKey.ENTER) },
                    onDone = { onKey(VtermKey.ENTER) },
                    onGo = { onKey(VtermKey.ENTER) },
                ),
            )
        }

        ModifierBar(
            ctrlActive = ctrlActive,
            altActive = altActive,
            onToggleCtrl = onToggleCtrl,
            onToggleAlt = onToggleAlt,
            onSendKey = onKey,
            onSendChar = onChar,
        )
    }
}

/**
 * What the input field holds between keystrokes.
 *
 * A zero-width space rather than an ordinary one: a keyboard that looks at the
 * text before the cursor - for auto-capitalisation or word suggestions - sees
 * nothing that ends a word or a sentence, and so has no reason to capitalise
 * the next letter or insert a space of its own.
 */
private const val PLACEHOLDER = "\u200B"

private fun emptyInput() = TextFieldValue(PLACEHOLDER, selection = TextRange(PLACEHOLDER.length))
