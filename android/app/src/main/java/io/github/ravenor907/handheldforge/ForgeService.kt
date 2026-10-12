package io.github.ravenor907.handheldforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.handheldforge.core.Catalog
import io.github.ravenor907.handheldforge.core.CancelledException
import io.github.ravenor907.handheldforge.core.Distro
import io.github.ravenor907.handheldforge.core.Downloader
import io.github.ravenor907.handheldforge.core.ImageWriter
import io.github.ravenor907.handheldforge.core.Stage
import io.github.ravenor907.handheldforge.usb.UsbSticks
import java.io.File
import java.io.IOException

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
        ServiceCompat.startForeground(
            this, NOTIFY_ID, notification("Starting", 0, 0),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        worker = Thread({ run(distroId, stickKey) }, "forge").also { it.start() }
        return START_NOT_STICKY
    }

    private fun run(distroId: String, stickKey: String) {
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HandheldForge:write")
        wake.acquire(4 * 60 * 60 * 1000L)
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
                if (distro.format == "raw-bz2") {
                    try {
                        ImageWriter.writeStream(
                            img.file,
                            { org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(it, true) },
                            s.disk, null, stage, Jobs::log, cancel,
                        )
                    } catch (e: IOException) {
                        if (e is io.github.ravenor907.handheldforge.core.WriteException || e.message?.contains("CRC", true) != true) throw e
                        img.file.delete() // corrupt download: fetch a fresh copy next time
                        throw IOException("The download was corrupted, so it was deleted. Try again.")
                    }
                } else {
                    ImageWriter.write(img.file, s.disk, img.sha256, stage, Jobs::log, cancel)
                }
            }
            Jobs.finish(Outcome.OK, "")
            done("${distro.name} is ready to boot")
        } catch (e: CancelledException) {
            val wasWriting = (Jobs.state.value as? JobState.Running)?.stage?.let { it >= Stage.WRITE } == true
            Jobs.finish(
                Outcome.CANCELLED,
                if (wasWriting) {
                    "Stopped. The drive is unfinished, so create it again before booting from it."
                } else {
                    "Download paused. It picks up where it left off next time."
                },
            )
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
        val text = when (s) {
            Stage.LOOKUP -> "Checking for the latest release"
            Stage.DOWNLOAD -> "Downloading"
            Stage.VERIFY -> "Making sure the download is genuine"
            Stage.WRITE -> "Writing to the USB drive"
            Stage.CHECK -> "Reading the drive back to confirm"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification(text, d, t))
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
        private const val CHANNEL = "forge"
        private const val NOTIFY_ID = 1

        fun start(context: Context, distro: Distro, stickKey: String, stickLabel: String) {
            Jobs.begin(distro.name, stickLabel)
            val i = Intent(context, ForgeService::class.java)
                .putExtra(EXTRA_DISTRO, distro.id)
                .putExtra(EXTRA_STICK, stickKey)
            ContextCompat.startForegroundService(context, i)
        }

        fun catalog(context: Context): List<Distro> =
            Catalog.parse(context.assets.open("distros.json").bufferedReader().use { it.readText() })

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
