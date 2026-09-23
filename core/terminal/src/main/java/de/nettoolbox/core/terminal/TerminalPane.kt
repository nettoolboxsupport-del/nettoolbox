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
 * character typed into it is forwarded and the field is emptied again at once,
 * so it never holds any text and autocorrect has nothing to work on.
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
    var rawInputText by remember { mutableStateOf(TextFieldValue("")) }

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
                    if (text.isNotEmpty()) {
                        for (c in text) {
                            if (c == '\n' || c == '\r') onKey(VtermKey.ENTER) else onChar(c)
                        }
                        rawInputText = TextFieldValue("")
                    }
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
