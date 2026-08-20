package de.nettoolbox.core.permissions

/**
 * What Android will do the next time the app asks.
 *
 * [NOT_REQUESTED] and [DENIED] look identical to `checkSelfPermission`; telling
 * them apart needs the app's own record of whether it has ever asked. Without
 * that distinction a screen cannot know whether the system dialog will still
 * appear, and would offer a "grant" button that does nothing.
 */
enum class PermissionStatus {
    GRANTED,
    NOT_REQUESTED,
    DENIED,
    PERMANENTLY_DENIED,
    ;

    val isGranted: Boolean get() = this == GRANTED

    /** True when only the system settings page can still change this. */
    val needsSettings: Boolean get() = this == PERMANENTLY_DENIED
}
