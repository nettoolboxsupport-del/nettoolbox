package de.nettoolbox.core.ui.component

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import de.nettoolbox.core.ui.R
import de.nettoolbox.core.ui.icon.NetToolboxIcons

/**
 * Copy / share / export, identical under every tool result.
 *
 * Copy and share are handled here rather than by each caller: they are the same
 * two platform calls every time, and centralising them means a tool cannot ship
 * a result view that silently lacks them.
 *
 * @param plainText the result in the form it should land on the clipboard or in
 *   a share sheet - already formatted, not a data object
 * @param onExport writing a file needs a format decision and a SAF picker, so it
 *   stays with the caller; pass null to hide the button
 */
@Composable
fun ResultActionBar(
    plainText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onExport: (() -> Unit)? = null,
    onCopied: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_chooser_title)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(
            icon = NetToolboxIcons.Copy,
            label = stringResource(R.string.action_copy),
            enabled = enabled,
            onClick = {
                clipboard.setText(AnnotatedString(plainText))
                onCopied()
            },
        )

        ActionButton(
            icon = NetToolboxIcons.Share,
            label = stringResource(R.string.action_share),
            enabled = enabled,
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, plainText)
                }
                context.startActivity(Intent.createChooser(send, shareTitle))
            },
        )

        if (onExport != null) {
            ActionButton(
                icon = NetToolboxIcons.Export,
                label = stringResource(R.string.action_export),
                enabled = enabled,
                onClick = onExport,
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
