package de.nettoolbox.core.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.nettoolbox.vterm.VtermKey
import de.nettoolbox.vterm.VtermModifier

/**
 * On-screen auxiliary toolbar for special terminal keys and latching modifiers.
 */
@Composable
fun ModifierBar(
    ctrlActive: Boolean,
    altActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendKey: (key: Int) -> Unit,
    onSendChar: (char: Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFnMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ESC
            ToolbarKeyButton(
                label = stringResource(R.string.terminal_mod_esc),
                onClick = { onSendKey(VtermKey.ESCAPE) },
            )

            // TAB
            ToolbarKeyButton(
                label = stringResource(R.string.terminal_mod_tab),
                onClick = { onSendKey(VtermKey.TAB) },
            )

            // ENTER
            ToolbarKeyButton(
                label = "⏎ Enter",
                onClick = { onSendKey(VtermKey.ENTER) },
            )

            // BACKSPACE
            ToolbarKeyButton(
                label = "⌫",
                onClick = { onSendKey(VtermKey.BACKSPACE) },
            )

            // CTRL (Latching toggle)
            ToolbarToggleButton(
                label = stringResource(R.string.terminal_mod_ctrl),
                active = ctrlActive,
                onClick = onToggleCtrl,
            )

            // ALT (Latching toggle)
            ToolbarToggleButton(
                label = stringResource(R.string.terminal_mod_alt),
                active = altActive,
                onClick = onToggleAlt,
            )

            // Arrow Keys
            ToolbarKeyButton(label = "▲", onClick = { onSendKey(VtermKey.UP) })
            ToolbarKeyButton(label = "▼", onClick = { onSendKey(VtermKey.DOWN) })
            ToolbarKeyButton(label = "◀", onClick = { onSendKey(VtermKey.LEFT) })
            ToolbarKeyButton(label = "▶", onClick = { onSendKey(VtermKey.RIGHT) })

            // Quick shortcuts
            ToolbarKeyButton(label = "Ctrl+C", onClick = { onSendChar('\u0003') })
            ToolbarKeyButton(label = "Ctrl+D", onClick = { onSendChar('\u0004') })
            ToolbarKeyButton(label = "/", onClick = { onSendChar('/') })
            ToolbarKeyButton(label = "-", onClick = { onSendChar('-') })
            ToolbarKeyButton(label = "|", onClick = { onSendChar('|') })
            ToolbarKeyButton(label = "~", onClick = { onSendChar('~') })

            // Fn Dropdown menu
            Box {
                ToolbarKeyButton(
                    label = stringResource(R.string.terminal_mod_fn),
                    onClick = { showFnMenu = true },
                )
                DropdownMenu(
                    expanded = showFnMenu,
                    onDismissRequest = { showFnMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Home") },
                        onClick = {
                            onSendKey(VtermKey.HOME)
                            showFnMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("End") },
                        onClick = {
                            onSendKey(VtermKey.END)
                            showFnMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Page Up") },
                        onClick = {
                            onSendKey(VtermKey.PAGEUP)
                            showFnMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Page Down") },
                        onClick = {
                            onSendKey(VtermKey.PAGEDOWN)
                            showFnMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Insert") },
                        onClick = {
                            onSendKey(VtermKey.INS)
                            showFnMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            onSendKey(VtermKey.DEL)
                            showFnMenu = false
                        },
                    )
                    for (i in 1..12) {
                        DropdownMenuItem(
                            text = { Text("F$i") },
                            onClick = {
                                onSendKey(VtermKey.function(i))
                                showFnMenu = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarKeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ToolbarToggleButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
