package de.nettoolbox.feature.cellular.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Positions from the platform's own [LocationManager].
 *
 * No Google Play Services: the spec rules out a GMS dependency, and a drive test
 * that only works on a Google-certified device is useless on the hardware field
 * crews actually carry. Fused Location would be smoother but is optional at best.
 *
 * GPS and network provider are both requested. GPS is the accurate one; the
 * network provider keeps a session producing positions in a building or a tunnel,
 * where GPS delivers nothing at all.
 */
@Singleton
class LocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun isGpsEnabled(): Boolean =
        context.getSystemService<LocationManager>()
            ?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    /**
     * @param minDistanceMeters 0 disables distance filtering. Above 0 this is the
     *   battery saver from the spec: standing still produces no new samples.
     */
    @SuppressLint("MissingPermission")
    fun observe(
        minIntervalMillis: Long,
        minDistanceMeters: Float,
    ): Flow<Location> = callbackFlow {
        val manager = context.getSystemService<LocationManager>()
        if (manager == null) {
            awaitClose { }
            return@callbackFlow
        }

        // Not a SAM conversion: on Android 9 the three legacy callbacks are still
        // abstract, so the interface has to be implemented in full.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            @Deprecated("Required on API 28, removed from the interface later")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

        PROVIDERS.forEach { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(
                        provider,
                        minIntervalMillis,
                        minDistanceMeters,
                        listener,
                        Looper.getMainLooper(),
                    )
                }
            }
        }

        // A last known position gets the first samples positioned instead of
        // waiting for the first fix, which can take a minute under a cold start.
        PROVIDERS.firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }?.let { trySend(it) }

        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

    private companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
