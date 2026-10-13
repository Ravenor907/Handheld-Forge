package io.github.ravenor907.handheldforge.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.ravenor907.handheldforge.core.BulkOnly
import io.github.ravenor907.handheldforge.core.ScsiDisk
import io.github.ravenor907.handheldforge.core.UsbTransport
import java.io.Closeable
import java.io.IOException

/** A plugged-in USB stick as the UI sees it. */
data class Stick(
    val key: String, // UsbDevice.deviceName, e.g. /dev/bus/usb/001/004
    val label: String,
    val hasPermission: Boolean,
    val sizeBytes: Long?,
)

/** Android's USB host API wired to the protocol code in core/. */
class AndroidUsbTransport(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : UsbTransport, Closeable {
    // Before Android 9, a single bulk transfer was capped at 16 KiB.
    override val maxTransfer: Int = if (Build.VERSION.SDK_INT >= 28) 64 * 1024 else 16 * 1024

    // Each transfer waits for the last to finish, so small ones leave the bus idle most of the time.
    // Android 9 and later can pass much bigger ones straight to the kernel; the disk probes this first.
    override val largestTransfer: Int = if (Build.VERSION.SDK_INT >= 28) 1024 * 1024 else maxTransfer

    override fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        conn.bulkTransfer(outEp, data, offset, length, timeoutMs)

    override fun bulkIn(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        conn.bulkTransfer(inEp, data, offset, length, timeoutMs)

    override fun resetRecovery() {
        // Bulk-Only Mass Storage Reset, then CLEAR_FEATURE(ENDPOINT_HALT) on both pipes.
        conn.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, 5000)
        conn.controlTransfer(0x02, 0x01, 0, inEp.address, null, 0, 5000)
        conn.controlTransfer(0x02, 0x01, 0, outEp.address, null, 0, 5000)
    }

    override fun close() {
        try {
            conn.releaseInterface(intf)
        } finally {
            conn.close()
        }
    }
}

class OpenStick(val disk: ScsiDisk, private val transport: AndroidUsbTransport) : Closeable {
    override fun close() = transport.close()
}

class UsbSticks(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val sizes = HashMap<String, Long>()

    fun devices(): List<UsbDevice> = manager.deviceList.values.filter { massStorage(it) != null }

    fun device(key: String): UsbDevice? = manager.deviceList[key]

    fun hasPermission(d: UsbDevice) = manager.hasPermission(d)

    fun label(d: UsbDevice): String {
        val parts = listOfNotNull(d.manufacturerName?.trim(), d.productName?.trim()).filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "USB drive" else parts.distinct().joinToString(" ")
    }

    /** Current sticks; sizes are probed once per stick (needs permission). */
    fun list(probe: Boolean): List<Stick> = devices().map { d ->
        val ok = hasPermission(d)
        var size = sizes[d.deviceName]
        if (ok && size == null && probe) {
            size = try {
                open(d).use { it.disk.sizeBytes }
            } catch (e: Exception) {
                null
            }
            if (size != null) sizes[d.deviceName] = size
        }
        Stick(d.deviceName, label(d), ok, size)
    }

    fun requestPermission(d: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        manager.requestPermission(d, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    /** Calls [onResult] whenever the user answers a permission request. */
    fun registerPermissionReceiver(onResult: () -> Unit): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onResult()
        }
        ContextCompat.registerReceiver(context, r, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        return r
    }

    fun open(d: UsbDevice): OpenStick {
        val intf = massStorage(d) ?: throw IOException("That device isn't a USB drive")
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        if (inEp == null || outEp == null) throw IOException("The drive has no bulk endpoints")
        val conn = manager.openDevice(d) ?: throw IOException("Android wouldn't open the drive. Unplug it and try again.")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("The drive is busy. Eject it in Android's storage settings, then try again.")
        }
        val t = AndroidUsbTransport(conn, intf, inEp, outEp)
        return try {
            OpenStick(ScsiDisk(BulkOnly(t)).init(), t)
        } catch (e: Exception) {
            t.close()
            throw e
        }
    }

    companion object {
        const val ACTION_PERMISSION = "io.github.ravenor907.handheldforge.USB_PERMISSION"

        fun massStorage(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    it.interfaceSubclass == 6 && it.interfaceProtocol == 0x50
            }
    }
}
