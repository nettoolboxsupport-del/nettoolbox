package de.nettoolbox.feature.serial.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.feature.serial.domain.SerialChip
import de.nettoolbox.feature.serial.domain.SerialDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The USB side: which adapters are plugged in, and permission to use them.
 *
 * No manifest permission is involved anywhere here. Android asks the user per
 * device, the first time an app wants it - and when the app is opened by the
 * system in response to plugging the adapter in (see the device filter in
 * :app), accepting that dialog grants the permission as well.
 */
@Singleton
class UsbSerialDevices @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val usbManager: UsbManager? = context.getSystemService()

    private val prober: UsbSerialProber = UsbSerialProber.getDefaultProber()

    /** False on a device without USB host support; the screen says so instead of showing an empty list. */
    val hostSupported: Boolean
        get() = usbManager != null &&
            context.packageManager.hasSystemFeature("android.hardware.usb.host")

    /** Every attached device a serial driver recognises. */
    fun scan(): List<SerialDevice> {
        val manager = usbManager ?: return emptyList()
        return runCatching { prober.findAllDrivers(manager) }
            .getOrDefault(emptyList())
            .map { describe(it, manager) }
            .sortedBy { it.deviceName }
    }

    /** The driver for a device found by [scan], or null if it has gone. */
    fun driverFor(deviceName: String): UsbSerialDriver? {
        val manager = usbManager ?: return null
        val device = manager.deviceList[deviceName] ?: return null
        return prober.probeDevice(device)
    }

    fun isAttached(deviceName: String): Boolean =
        usbManager?.deviceList?.containsKey(deviceName) == true

    fun hasPermission(device: UsbDevice): Boolean = usbManager?.hasPermission(device) == true

    fun openConnection(device: UsbDevice): UsbDeviceConnection? = usbManager?.openDevice(device)

    /** Emits whenever a USB device is attached or detached, so the list can be refreshed. */
    val changes: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // NOT_EXPORTED: both actions are system broadcasts, which such a
        // receiver still gets; what it refuses is the same action forged by
        // another app.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * Asks the user for access to one device and suspends until they answer.
     *
     * The shape - FLAG_MUTABLE, an explicit package on the intent, and a
     * NOT_EXPORTED receiver - follows the library's own example project. Mutable
     * because the system adds the result to the intent as extras; explicit
     * because Android 14 refuses mutable PendingIntents with implicit intents;
     * not exported because nothing but this app's own PendingIntent has any
     * business delivering a "permission granted" to it.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        val manager = usbManager ?: return false
        if (manager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (continuation.isActive) continuation.resume(granted)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
    }

    private fun describe(driver: UsbSerialDriver, manager: UsbManager): SerialDevice {
        val device = driver.device
        val permitted = manager.hasPermission(device)
        return SerialDevice(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            chip = chipOf(driver),
            // Both getters throw SecurityException on Android 10 and later until
            // permission is granted. Not an error - the names simply are not
            // known yet.
            manufacturer = if (permitted) runCatching { device.manufacturerName }.getOrNull() else null,
            product = if (permitted) runCatching { device.productName }.getOrNull() else null,
            portCount = driver.ports.size,
            hasPermission = permitted,
        )
    }

    private fun chipOf(driver: UsbSerialDriver): SerialChip = when (driver) {
        is FtdiSerialDriver -> SerialChip.FTDI
        is Cp21xxSerialDriver -> SerialChip.CP210X
        is ProlificSerialDriver -> SerialChip.PL2303
        is Ch34xSerialDriver -> SerialChip.CH34X
        is CdcAcmSerialDriver -> SerialChip.CDC_ACM
        else -> SerialChip.OTHER
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "de.nettoolbox.serial.USB_PERMISSION"
    }
}
