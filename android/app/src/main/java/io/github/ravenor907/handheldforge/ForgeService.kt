package io.github.ravenor907.handheldforge

import android.net.Uri
import android.provider.OpenableColumns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.handheldforge.core.Catalog
import io.github.ravenor907.handheldforge.core.CancelledException
import io.github.ravenor907.handheldforge.core.CorruptImageException
import io.github.ravenor907.handheldforge.core.Distro
import io.github.ravenor907.handheldforge.core.DownloadException
import io.github.ravenor907.handheldforge.core.Preflight
import io.github.ravenor907.handheldforge.core.Downloader
import io.github.ravenor907.handheldforge.core.ImageWriter
import io.github.ravenor907.handheldforge.core.Progress
import io.github.ravenor907.handheldforge.core.Stage
import io.github.ravenor907.handheldforge.usb.UsbSticks
import java.io.File
import java.io.IOException
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Runs download, verify, write and read-back in the foreground so the phone can sleep the screen. */
class ForgeService : Service() {
    private var worker: Thread? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val distroId = intent?.getStringExtra(EXTRA_DISTRO)
        val stickKey = intent?.getStringExtra(EXTRA_STICK)
        if (distroId == null || stickKey == null || worker != null) return START_NOT_STICKY

        ensureChannel()
        goForeground()
        val file = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        worker = Thread({ if (file != null) runFile(file, stickKey) else run(distroId, stickKey) }, "forge").also { it.start() }
        return START_NOT_STICKY
    }

    /**
     * "Connected device" has no time limit and fits a job that drives a USB stick; the
     * "data sync" type Android 15 caps at 6 hours a day is only the fallback.
     */
    private fun goForeground() {
        val n = notification("Starting", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ServiceCompat.startForeground(this, NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                return
            } catch (e: Exception) {
                Jobs.log("Running as a data sync service (${e.javaClass.simpleName})")
            }
            ServiceCompat.startForeground(this, NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ServiceCompat.startForeground(this, NOTIFY_ID, n, 0)
        }
    }

    /** Android 15 calls this when a data sync service hits its daily limit; stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Jobs.cancel("Android stopped the job because it ran too long in the background. Keep the app open and try again.")
    }

    /** Writes a file the person picked (an ISO or disk image), byte for byte, then reads it back. */
    private fun runFile(uri: Uri, stickKey: String) {
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HandheldForge:write")
        wake.acquire(4 * 60 * 60 * 1000L)
        try {
            val (name, size) = describeFile(this, uri)
            if (size <= 0) throw IOException("Couldn't read the size of $name. Copy it to the phone's storage and try again.")
            val decode = decoderFor(name)
            val sticks = UsbSticks(this)
            val device = sticks.device(stickKey) ?: throw IOException("The USB drive was unplugged.")
            val stage = { st: Stage, d: Long, t: Long ->
                Jobs.progress(st, d, t)
                maybeNotify(st, d, t)
            }
            Jobs.log("Writing $name (%.1f GB)".format(size / 1e9))
            sticks.open(device).use { s ->
                if (decode == null && s.disk.sizeBytes < size) throw IOException("This drive is too small for $name.")
                try {
                    ImageWriter.writeStream(
                        { contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open $name") },
                        size, decode ?: { it }, s.disk, null, stage, Jobs::log, { Jobs.cancelRequested },
                    )
                } catch (e: CorruptImageException) {
                    throw IOException("$name is damaged or isn't a compressed disk image. Download it again.")
                }
            }
            Jobs.finish(Outcome.OK, "")
            done("$name is on the drive")
        } catch (e: CancelledException) {
            Jobs.finish(Outcome.CANCELLED, listOfNotNull(Jobs.cancelReason, "The drive is unfinished, so write it again before booting from it.").joinToString(" "))
            done("Stopped")
        } catch (e: Exception) {
            Jobs.log("${e.javaClass.simpleName}: ${e.message}")
            Jobs.finish(Outcome.ERROR, e.message ?: e.javaClass.simpleName)
            done("The drive wasn't finished")
        } finally {
            if (wake.isHeld) wake.release()
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun run(distroId: String, stickKey: String) {
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HandheldForge:write")
        wake.acquire(4 * 60 * 60 * 1000L)
        // Keeps Wi-Fi at full speed with the screen off; without it some phones throttle or drop it.
        @Suppress("DEPRECATION")
        val wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HandheldForge:download")
            .apply { setReferenceCounted(false) }
        try {
            wifi.acquire()
        } catch (e: Exception) {
            Jobs.log("No Wi-Fi lock: ${e.message}")
        }
        try {
            val distro = catalog(this).first { it.id == distroId }
            val sticks = UsbSticks(this)
            val cancel = { Jobs.cancelRequested }
            val stage = { s: Stage, d: Long, t: Long ->
                Jobs.progress(s, d, t)
                maybeNotify(s, d, t)
            }

            // Check the stick before spending time on a big download.
            val device = sticks.device(stickKey) ?: throw IOException("The USB drive was unplugged.")
            sticks.open(device).use { s ->
                Jobs.log("Drive: ${s.disk.name}, ${"%.1f".format(s.disk.sizeBytes / 1e9)} GB")
                if (s.disk.sizeBytes < distro.minDriveBytes) {
                    throw IOException("This drive is too small. ${distro.name} needs ${distro.minDriveGb} GB or more.")
                }
            }

            val downloader = Downloader(
                isoDir(this),
                readKey = { path -> assets.open(path).bufferedReader().use { it.readText() } },
                userAgent = "HandheldForge-Android/${BuildConfigCompat.versionName(this)}",
            )
            val img = downloader.prepare(distro, stage, Jobs::log, cancel)
            if (cancel()) throw CancelledException()

            val again = sticks.device(stickKey)
                ?: throw IOException("The USB drive was unplugged during the download. Plug it back in and try again.")
            sticks.open(again).use { s ->
                try {
                    if (distro.format == "raw-bz2") {
                        // No published checksum: bzip2's block CRCs catch a damaged download.
                        ImageWriter.writeStream(
                            img.file, { BZip2CompressorInputStream(it, true) },
                            s.disk, null, stage, Jobs::log, cancel,
                        )
                    } else {
                        ImageWriter.write(img.file, s.disk, img.sha256, stage, Jobs::log, cancel)
                    }
                } catch (e: CorruptImageException) {
                    Jobs.log(e.message ?: "")
                    img.file.delete() // fetch a fresh copy next time
                    throw IOException("The download was corrupted, so it was deleted. Try again.")
                }
            }
            Jobs.finish(Outcome.OK, "")
            done("${distro.name} is ready to boot")
        } catch (e: CancelledException) {
            val wasWriting = (Jobs.state.value as? JobState.Running)?.stage?.let { it >= Stage.WRITE } == true
            Jobs.finish(
                Outcome.CANCELLED,
                listOfNotNull(
                    Jobs.cancelReason,
                    if (wasWriting) {
                        "The drive is unfinished, so create it again before booting from it."
                    } else {
                        "Download paused. It picks up where it left off next time."
                    },
                ).joinToString(" "),
            )
            done("Stopped")
        } catch (e: Exception) {
            Jobs.log("${e.javaClass.simpleName}: ${e.message}")
            val networkError = e is java.net.SocketException || e is java.net.SocketTimeoutException ||
                e is java.net.UnknownHostException || e is javax.net.ssl.SSLException ||
                e.message == "Connection closed early" ||
                (e is DownloadException && e.message?.startsWith("Couldn't reach") == true)
            Jobs.finish(Outcome.ERROR, Preflight.explain(e.message ?: e.javaClass.simpleName, Jobs.leftDuringJob, networkError))
            done("The drive wasn't finished")
        } finally {
            if (wifi.isHeld) wifi.release()
            if (wake.isHeld) wake.release()
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(text: String, done: Long, total: Long): Notification {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setProgress(100, pct, total <= 0)
            .build()
    }

    private fun maybeNotify(s: Stage, d: Long, t: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 1000) return
        lastNotify = now
        // The same overall percentage and step as the app's screen.
        val job = Jobs.state.value as? JobState.Running ?: return
        val pct = (job.fraction * 100).toInt().coerceIn(0, 100)
        val text = "$pct%  " + Progress.describe(s, d, t, 0, job.distro, local = job.local).first
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification(text, pct.toLong(), 100))
    }

    private fun done(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n)
    }

    companion object {
        const val EXTRA_DISTRO = "distro"
        const val EXTRA_STICK = "stick"
        const val EXTRA_URI = "uri"
        const val CUSTOM_ID = "custom-file"

        /** File types the picker offers. Compressed images are unpacked while they're written. */
        val PICK_TYPES = arrayOf(
            "application/x-iso9660-image", "application/octet-stream", "application/x-raw-disk-image",
            "application/x-bzip2", "application/gzip", "application/x-gzip", "application/x-cd-image",
        )

        /** null for an uncompressed image, else a decoder for .bz2 / .gz. */
        fun decoderFor(name: String): ((java.io.InputStream) -> java.io.InputStream)? = when {
            name.endsWith(".bz2", true) -> { s -> BZip2CompressorInputStream(s, true) }
            name.endsWith(".gz", true) -> { s -> java.util.zip.GZIPInputStream(s, 1 shl 16) }
            else -> null
        }

        /** (display name, size in bytes) of a picked file. */
        fun describeFile(context: Context, uri: Uri): Pair<String, Long> {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            return name to size
        }

        fun startFile(context: Context, uri: Uri, name: String, stickKey: String, stickLabel: String) {
            Jobs.begin(name, stickLabel, local = true)
            val i = Intent(context, ForgeService::class.java)
                .putExtra(EXTRA_DISTRO, CUSTOM_ID)
                .putExtra(EXTRA_STICK, stickKey)
                .putExtra(EXTRA_URI, uri.toString())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(context, i)
        }
        private const val CHANNEL = "forge"
        private const val NOTIFY_ID = 1

        fun start(context: Context, distro: Distro, stickKey: String, stickLabel: String) {
            Jobs.begin(distro.name, stickLabel)
            val i = Intent(context, ForgeService::class.java)
                .putExtra(EXTRA_DISTRO, distro.id)
                .putExtra(EXTRA_STICK, stickKey)
            ContextCompat.startForegroundService(context, i)
        }

        private fun catalogText(context: Context) = context.assets.open("distros.json").bufferedReader().use { it.readText() }

        fun catalog(context: Context): List<Distro> = Catalog.parse(catalogText(context))

        fun bootMenu(context: Context): List<String> = Catalog.bootMenu(catalogText(context))

        fun isoDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "iso")
    }
}

/** Version name without generating BuildConfig. */
object BuildConfigCompat {
    fun versionName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
