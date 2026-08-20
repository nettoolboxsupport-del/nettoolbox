package de.nettoolbox.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.nettoolbox.app.R
import de.nettoolbox.core.permissions.PermissionBundle
import de.nettoolbox.core.permissions.PermissionBundleState
import de.nettoolbox.core.permissions.PermissionCoordinator
import de.nettoolbox.core.permissions.rememberPermissionBundleState

/**
 * First-run introduction.
 *
 * Two things this screen deliberately does not do:
 *
 * **It does not block.** Every permission card can be skipped, and "Get
 * started" is always enabled. Each tool already refuses to render fake results
 * without its permission (see PermissionGate), so nothing here needs to be
 * mandatory. A first-run screen that holds the app hostage until every
 * permission is granted teaches users to tap "allow" without reading, which is
 * the opposite of informed consent.
 *
 * **It does not ask for everything at once.** The bundles are separate cards
 * with their own rationale, requested one at a time, because Android shows one
 * system dialog per request and a burst of them is indistinguishable from an
 * app that wants everything.
 */
@Composable
fun OnboardingScreen(
    coordinator: PermissionCoordinator,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cellular = rememberPermissionBundleState(PermissionBundle.CELLULAR_LIVE, coordinator)
    val wifi = rememberPermissionBundleState(PermissionBundle.WIFI_SCAN, coordinator)
    val notifications =
        rememberPermissionBundleState(PermissionBundle.SERVICE_NOTIFICATIONS, coordinator)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )

        // Stated before any permission is requested, not buried in the about
        // screen. It is the answer to the question a permission dialog raises.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.onboarding_privacy_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.onboarding_privacy_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
        Text(
            text = stringResource(R.string.onboarding_permissions_optional),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        PermissionCard(state = cellular, labelRes = R.string.onboarding_permission_cellular)
        PermissionCard(state = wifi, labelRes = R.string.onboarding_permission_wifi)
        PermissionCard(
            state = notifications,
            labelRes = R.string.onboarding_permission_notifications,
        )

        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 16.dp),
        ) {
            Text(stringResource(R.string.onboarding_start))
        }
    }
}

@Composable
private fun PermissionCard(state: PermissionBundleState, labelRes: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.titleSmall,
            )
            // The rationale text already lives with the bundle, so the reason
            // shown here and the reason shown when a tool blocks are the same
            // sentence. Two wordings for one permission is how users end up
            // distrusting both.
            Text(
                text = stringResource(state.bundle.rationaleRes),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )

            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                when {
                    state.allGranted -> Text(
                        text = stringResource(R.string.onboarding_granted),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    // Android stops showing the dialog after two refusals, so
                    // offering "Allow" again would do nothing at all.
                    state.needsSettings -> TextButton(onClick = state::openSettings) {
                        Text(stringResource(R.string.onboarding_open_settings))
                    }

                    else -> TextButton(onClick = state::request) {
                        Text(stringResource(R.string.onboarding_grant))
                    }
                }
            }
        }
    }
}
