package de.nettoolbox.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.ui.R
import de.nettoolbox.core.ui.error.errorHint
import de.nettoolbox.core.ui.error.errorTitle
import de.nettoolbox.core.ui.icon.NetToolboxIcons
import de.nettoolbox.core.ui.theme.MonospaceTextStyle
import de.nettoolbox.core.ui.theme.NetToolboxTheme

/**
 * Shared layout for the four screen states. Every feature renders these instead
 * of inventing its own empty or error view, so "no permission" looks the same in
 * the Wi-Fi analyzer as it does in the cellular module.
 */
@Composable
private fun MessageState(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    technicalDetail: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = iconTint,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (technicalDetail != null) {
            Text(
                text = technicalDetail,
                style = MonospaceTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 20.dp),
        ) {
            actions()
        }
    }
}

@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.state_loading),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.state_empty_title),
    description: String = stringResource(R.string.state_empty_description),
    @DrawableRes icon: Int = NetToolboxIcons.Search,
    onRetry: (() -> Unit)? = null,
) {
    MessageState(
        icon = icon,
        title = title,
        description = description,
        modifier = modifier,
        actions = {
            if (onRetry != null) {
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        },
    )
}

/**
 * Error view driven by the structured error domain. The recovery hint is not
 * optional - a dead-end error message is exactly what the spec forbids.
 */
@Composable
fun ErrorState(
    error: NetToolboxError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val permission = error.reason == ErrorReason.PERMISSION_DENIED ||
        error.reason == ErrorReason.PERMISSION_PERMANENTLY_DENIED

    MessageState(
        icon = if (permission) NetToolboxIcons.Lock else NetToolboxIcons.Warning,
        title = errorTitle(error),
        description = errorHint(error),
        modifier = modifier,
        iconTint = MaterialTheme.colorScheme.error,
        technicalDetail = error.detail,
        actions = {
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            if (onOpenSettings != null) {
                OutlinedButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.action_open_settings))
                }
            }
        },
    )
}

/**
 * Blocked state for a screen whose permissions are missing. Rendered instead of
 * the feature, never as an overlay on fabricated data.
 *
 * @param permanentlyDenied when true the system dialog will no longer appear, so
 *   the only way forward is the app's settings page
 */
@Composable
fun PermissionRequiredState(
    rationale: String,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    permanentlyDenied: Boolean = false,
    title: String = stringResource(R.string.error_title_permission),
) {
    MessageState(
        icon = NetToolboxIcons.Lock,
        title = title,
        description = rationale,
        modifier = modifier,
        actions = {
            if (permanentlyDenied) {
                Button(onClick = onOpenSettings) {
                    Text(stringResource(R.string.action_open_settings))
                }
            } else {
                Button(onClick = onRequest) {
                    Text(stringResource(R.string.action_grant_permission))
                }
                OutlinedButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.action_open_settings))
                }
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ErrorStatePreview() {
    NetToolboxTheme {
        ErrorState(
            error = NetToolboxError(
                reason = ErrorReason.SCAN_THROTTLED,
                retryAfterMillis = 47_000L,
            ),
            onRetry = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
