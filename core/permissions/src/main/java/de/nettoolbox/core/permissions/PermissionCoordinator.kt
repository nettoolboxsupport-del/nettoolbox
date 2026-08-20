package de.nettoolbox.core.permissions

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single place that answers "may this feature run, and if not, what now".
 *
 * The "has it ever been asked" flag is kept in SharedPreferences rather than in
 * the project's DataStore: it is three booleans of bookkeeping that must be
 * readable synchronously while composing a permission gate, not user settings.
 */
@Singleton
class PermissionCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val sdkInt: Int get() = Build.VERSION.SDK_INT

    fun isGranted(permission: AppPermission): Boolean =
        ContextCompat.checkSelfPermission(context, permission.manifestName) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Full status. [activity] is needed for the rationale check that separates a
     * plain denial from a permanent one; without it the result degrades to
     * [PermissionStatus.DENIED], which is the safe assumption.
     */
    fun statusOf(permission: AppPermission, activity: Activity?): PermissionStatus = when {
        !permission.isRelevantOn(sdkInt) -> PermissionStatus.GRANTED
        isGranted(permission) -> PermissionStatus.GRANTED
        !hasBeenRequested(permission) -> PermissionStatus.NOT_REQUESTED
        activity == null -> PermissionStatus.DENIED
        ActivityCompat.shouldShowRequestPermissionRationale(activity, permission.manifestName) ->
            PermissionStatus.DENIED

        else -> PermissionStatus.PERMANENTLY_DENIED
    }

    fun statusOf(bundle: PermissionBundle, activity: Activity?): Map<AppPermission, PermissionStatus> =
        bundle.permissionsFor(sdkInt).associateWith { statusOf(it, activity) }

    fun permissionsToRequest(bundle: PermissionBundle): List<AppPermission> =
        bundle.permissionsFor(sdkInt).filterNot { isGranted(it) }

    fun hasBeenRequested(permission: AppPermission): Boolean =
        prefs.getBoolean(permission.name, false)

    fun markRequested(permissions: Collection<AppPermission>) {
        if (permissions.isEmpty()) return
        prefs.edit().apply {
            permissions.forEach { putBoolean(it.name, true) }
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "nettoolbox_permission_history"
    }
}
