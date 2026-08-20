package de.nettoolbox.core.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.nettoolbox.core.ui.component.PermissionRequiredState

/**
 * Live permission state for one [PermissionBundle].
 *
 * Re-reads on every ON_RESUME, because the user can change permissions in the
 * system settings while the app sits in the background - a screen that caches
 * the answer from its first composition would keep showing a blocked state after
 * the user has already granted access.
 */
@Stable
class PermissionBundleState internal constructor(
    val bundle: PermissionBundle,
    internal val statuses: Map<AppPermission, PermissionStatus>,
    private val onRequest: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    val allGranted: Boolean get() = statuses.values.all { it.isGranted }

    /** True when the system dialog will no longer appear for at least one member. */
    val needsSettings: Boolean get() = statuses.values.any { it.needsSettings }

    val missing: List<AppPermission> get() = statuses.filterValues { !it.isGranted }.keys.toList()

    fun request() = onRequest()

    fun openSettings() = onOpenSettings()
}

@Composable
fun rememberPermissionBundleState(
    bundle: PermissionBundle,
    coordinator: PermissionCoordinator,
): PermissionBundleState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var statuses by remember(bundle) {
        mutableStateOf(coordinator.statusOf(bundle, activity))
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // The result map is ignored on purpose: the coordinator is the single
        // source of truth, and it also knows about the "asked before" flag.
        statuses = coordinator.statusOf(bundle, activity)
    }

    DisposableEffect(lifecycleOwner, bundle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                statuses = coordinator.statusOf(bundle, activity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(bundle, statuses) {
        PermissionBundleState(
            bundle = bundle,
            statuses = statuses,
            onRequest = {
                val pending = coordinator.permissionsToRequest(bundle)
                if (pending.isNotEmpty()) {
                    coordinator.markRequested(pending)
                    launcher.launch(pending.map { it.manifestName }.toTypedArray())
                }
            },
            onOpenSettings = { context.openAppSettings() },
        )
    }
}

/**
 * Renders [content] only when the bundle is fully granted, and a blocked state
 * otherwise. This is what keeps every feature screen from crashing or, worse,
 * rendering empty data that looks like a real measurement.
 */
@Composable
fun PermissionGate(
    bundle: PermissionBundle,
    coordinator: PermissionCoordinator,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPermissionBundleState(bundle, coordinator)

    if (state.allGranted) {
        content()
    } else {
        PermissionRequiredState(
            rationale = stringResource(bundle.rationaleRes),
            onRequest = state::request,
            onOpenSettings = state::openSettings,
            permanentlyDenied = state.needsSettings,
            modifier = modifier,
        )
    }
}
