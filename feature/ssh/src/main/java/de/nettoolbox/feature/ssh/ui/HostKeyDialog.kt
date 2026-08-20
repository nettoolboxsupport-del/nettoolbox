package de.nettoolbox.feature.ssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.nettoolbox.core.ui.icon.NetToolboxIcons
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.feature.ssh.R
import de.nettoolbox.feature.ssh.domain.HostKeyVerdict

/**
 * Renders the appropriate host key decision dialog.
 *
 * Unknown (first contact) and Changed (potential MITM) are strictly separated
 * so that a dangerous change is never disguised by the milder wording of a first contact.
 */
@Composable
fun HostKeyDialog(
    verdict: HostKeyVerdict,
    onAccept: (HostKeyVerdict) -> Unit,
    onReject: () -> Unit,
) {
    when (verdict) {
        is HostKeyVerdict.Unknown -> UnknownHostKeyDialog(
            verdict = verdict,
            onAccept = { onAccept(verdict) },
            onReject = onReject,
        )

        is HostKeyVerdict.Changed -> ChangedHostKeyDialog(
            verdict = verdict,
            onAccept = { onAccept(verdict) },
            onReject = onReject,
        )

        HostKeyVerdict.Trusted -> Unit
    }
}

@Composable
private fun UnknownHostKeyDialog(
    verdict: HostKeyVerdict.Unknown,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val presented = verdict.presented
    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                painter = painterResource(NetToolboxIcons.Lock),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text(
                text = stringResource(R.string.ssh_dialog_unknown_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.ssh_dialog_unknown_message, presented.hostId, 22),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.ssh_dialog_key_type, presented.keyType),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = presented.fingerprintSha256,
                            style = MonospaceTextStyle.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text(stringResource(R.string.ssh_dialog_trust_button))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onReject) {
                Text(stringResource(R.string.ssh_dialog_cancel_button))
            }
        },
    )
}

@Composable
private fun ChangedHostKeyDialog(
    verdict: HostKeyVerdict.Changed,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val presented = verdict.presented
    val stored = verdict.stored

    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                painter = painterResource(NetToolboxIcons.Warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp),
            )
        },
        title = {
            Text(
                text = stringResource(R.string.ssh_dialog_changed_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.ssh_dialog_changed_message, presented.hostId, 22),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Previously trusted key
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = stringResource(R.string.ssh_dialog_stored_fingerprint, ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stored.fingerprintSha256,
                            style = MonospaceTextStyle.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Newly presented key
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = stringResource(R.string.ssh_dialog_presented_fingerprint, ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = presented.fingerprintSha256,
                            style = MonospaceTextStyle.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
        confirmButton = {
            // Abort is the primary/safe action
            Button(
                onClick = onReject,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(stringResource(R.string.ssh_dialog_reject_changed_button))
            }
        },
        dismissButton = {
            // Accepting the changed key is highlighted as dangerous
            TextButton(
                onClick = onAccept,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.ssh_dialog_accept_changed_button))
            }
        },
    )
}
