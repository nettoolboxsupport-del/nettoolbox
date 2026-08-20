package de.nettoolbox.core.permissions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens this app's page in the system settings - the only way back from a
 * permanently denied permission.
 */
fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

/**
 * Opens the developer options.
 *
 * Needed for one specific thing: from Android 10 on, Wi-Fi scan throttling can
 * only be switched off there. The Wi-Fi analyzer links here instead of pretending
 * it can scan faster than the platform allows.
 */
fun Context.openDeveloperOptions(): Boolean {
    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Not resolveActivity(): under package visibility that returns null on
    // perfectly resolvable system intents. Developer options are also hidden
    // until the user unlocks them, so failure is a normal outcome here.
    return try {
        startActivity(intent)
        true
    } catch (notFound: ActivityNotFoundException) {
        false
    }
}

/**
 * Unwraps the activity a composable is running in. Needed for the rationale
 * check, which is an activity-scoped call.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
