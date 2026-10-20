package io.github.ravenor907.handheldforge

import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.job.Stage
import io.github.ravenor907.ravendisk.core.job.Progress
import io.github.ravenor907.ravendisk.core.image.ImageKind
import io.github.ravenor907.ravendisk.core.bench.DriveTest
import io.github.ravenor907.ravendisk.core.fs.FsSupport
import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.job.SpeedPhase
import io.github.ravenor907.ravendisk.core.job.SpeedSample
import io.github.ravenor907.handheldforge.core.PlainDrive
import io.github.ravenor907.handheldforge.ui.SpeedGraph

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ravenor907.handheldforge.core.Distro
import io.github.ravenor907.handheldforge.core.Fix
import io.github.ravenor907.handheldforge.core.PhoneState
import io.github.ravenor907.handheldforge.core.Preflight
import io.github.ravenor907.handheldforge.core.VentoyConfig
import io.github.ravenor907.handheldforge.core.VentoyImport
import io.github.ravenor907.handheldforge.ui.Banner
import io.github.ravenor907.handheldforge.ui.CheckRow
import io.github.ravenor907.handheldforge.ui.EmptySlot
import io.github.ravenor907.handheldforge.ui.Forge
import io.github.ravenor907.handheldforge.ui.Gap
import io.github.ravenor907.handheldforge.ui.HeatBar
import io.github.ravenor907.handheldforge.ui.MoltenButton
import io.github.ravenor907.handheldforge.ui.QuietButton
import io.github.ravenor907.handheldforge.ui.RowGap
import io.github.ravenor907.handheldforge.ui.StatusMark
import io.github.ravenor907.handheldforge.ui.StepTrack
import io.github.ravenor907.handheldforge.ui.Tile
import io.github.ravenor907.handheldforge.ui.Txt
import io.github.ravenor907.handheldforge.usb.Stick
import io.github.ravenor907.handheldforge.usb.UsbSticks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CACHY = "cachyos-handheld"

/** Things the app can put on a drive besides a listed system. */
private val VENTOY = Distro(
    ForgeService.VENTOY_ID, "Ventoy", "Latest release", true, 1, null, emptyList(),
    installSteps = ventoySteps(secureBoot = true),
)

private fun ventoySteps(secureBoot: Boolean) = listOf(
    "Add images: tap Add images below, or copy ISO files onto the drive's Ventoy partition from a computer.",
    "Plug the drive into your handheld and start it from USB.",
    if (secureBoot) {
        "If Secure Boot is on, the first start shows a blue key screen: choose Enroll key from disk, open VTOYEFI, " +
            "pick ENROLL_THIS_KEY_IN_MOKMANAGER.cer, confirm, and restart. Only needed once per handheld."
    } else {
        "This drive was made without Secure Boot support, so turn Secure Boot off in the BIOS first."
    },
    "Ventoy shows a menu of the images on the drive. Pick one to start it. If the controls don't work in that menu, " +
        "tap an image under Add images and Ventoy will start it on its own.",
)

/** A file picked to add to a Ventoy drive. */
private data class Picked(val uri: String, val name: String, val size: Long, val kind: ImageKind)

/** What's on a Ventoy drive, read when its screen opens. */
private data class VentoyInfo(
    val version: String?,
    val secureBoot: Boolean,
    val free: Long,
    val largest: Long,
    val files: List<Pair<String, Long>>,
    /** Image Ventoy starts on its own (ventoy.json), or null. */
    val bootNext: String? = null,
    /** Set when the drive's ventoy.json couldn't be read, so boot-next can't be changed safely. */
    val configError: String? = null,
)
private val FORMAT = Distro(ForgeService.FORMAT_ID, "Format drive", "exFAT, FAT32 or ext4", true, 1, null, emptyList())
private val TOOLS = listOf(VENTOY, FORMAT)

val STEPS = Progress.STEPS

private fun stepOf(s: Stage) = Progress.stepOf(s)

private fun gb(bytes: Long) = when {
    bytes >= 10e9 -> "%.0f GB".format(bytes / 1e9)
    bytes >= 1e9 -> "%.1f GB".format(bytes / 1e9)
    else -> "%.0f MB".format(maxOf(1.0, bytes / 1e6))
}

class MainActivity : ComponentActivity() {
    private lateinit var sticks: UsbSticks
    private var receiver: BroadcastReceiver? = null
    private val permissionTick = MutableStateFlow(0)

    /** Bumped on every resume so the checklist re-reads the phone after a trip to Settings. */
    private val resumeTick = MutableStateFlow(0)
    private var demoPhone: PhoneState? = null
    private var bootMenu: List<String> = emptyList()

    /** Experimental systems (hidden in the catalog) show only after tapping the version number 10 times. */
    private val labs = MutableStateFlow(false)
    private var versionTaps = 0
    private var lastVersionTap = 0L

    private fun tapVersion() {
        val now = SystemClock.elapsedRealtime()
        versionTaps = if (now - lastVersionTap < 1500) versionTaps + 1 else 1
        lastVersionTap = now
        if (versionTaps < Labs.TAPS) return
        versionTaps = 0
        val on = !labs.value
        labs.value = on
        Labs.setOn(this, on)
        Toast.makeText(this, if (on) "Experimental options on" else "Experimental options off", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always light system-bar icons: the app is dark regardless of the phone's theme.
        val bars = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        sticks = UsbSticks(this)
        receiver = sticks.registerPermissionReceiver { permissionTick.value++ }
        val catalog = ForgeService.catalog(this)
        bootMenu = ForgeService.bootMenu(this)
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val demo = if (debuggable) intent.getStringExtra("demo") else null
        labs.value = Labs.isOn(this) || demo in setOf("labs", "confirm-labs", "labs-tools", "done-test")
        if (demo != null) startDemo(demo, catalog)
        setContent { App(catalog, demo) }
    }

    override fun onStart() {
        super.onStart()
        Jobs.setVisible(true)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }

    override fun onStop() {
        Jobs.setVisible(false)
        if (Jobs.state.value is JobState.Running && !isChangingConfigurations) {
            Toast.makeText(
                this, "Handheld Forge is still working. Come back and leave it open until it's done.", Toast.LENGTH_LONG,
            ).show()
        }
        super.onStop()
    }

    override fun onDestroy() {
        receiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun phoneState(): PhoneState {
        demoPhone?.let { return it }
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val pm = getSystemService(PowerManager::class.java)
        return PhoneState(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = plugged,
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            backgroundAllowed = pm?.isIgnoringBatteryOptimizations(packageName) == true,
        )
    }

    private fun fix(f: Fix) = when (f) {
        // The app's own settings page (Battery > Unrestricted). Google Play only allows the one-tap
        // prompt for a few kinds of apps, and the foreground service already covers the job itself.
        Fix.BACKGROUND -> try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private val demoSticks = listOf(
        Stick("demo1", "SanDisk Ultra Fit", true, 32_000_000_000, ventoy = true),
        Stick("demo2", "Kingston DataTraveler", false, null),
    )

    /** A made-up speed trace for screenshots: a stick that slows once its cache fills, then reads back. */
    private fun demoSamples(upTo: Double): List<SpeedSample> {
        val out = ArrayList<SpeedSample>()
        var t = 0.25
        var at = 0L
        while (t <= upTo) {
            val (phase, base) = when {
                t < 9 -> SpeedPhase.WRITE to 46.0
                t < 22 -> SpeedPhase.WRITE to 14.0
                t < 25 -> SpeedPhase.SCATTER_WRITE to 9.0
                t < 36 -> SpeedPhase.READ to 88.0
                else -> SpeedPhase.SCATTER_READ to 60.0
            }
            val wobble = kotlin.math.sin(t * 2.3) * base * 0.12 + kotlin.math.sin(t * 7.1) * base * 0.06
            val mbps = (base + wobble).coerceAtLeast(1.0)
            at += (mbps * 250_000).toLong()
            out.add(SpeedSample(phase, t, mbps, at))
            t += 0.25
        }
        return out
    }

    private fun startDemo(demo: String, catalog: List<Distro>) {
        val name = catalog.first { it.enabled }.name
        val lines = listOf("09:12:01  Using Live ISO (current installer)", "09:12:02  Latest sha256 4c1f0e9a...")
        demoPhone = when (demo) {
            "confirm" -> PhoneState(18, charging = false, online = true, unmetered = false, backgroundAllowed = false)
            else -> PhoneState(76, charging = true, online = true, unmetered = true, backgroundAllowed = true)
        }
        when (demo) {
            "working", "stop" -> Jobs.demo(
                JobState.Running(
                    name, "SanDisk Ultra Fit", Stage.WRITE, 4_100_000_000, 8_300_000_000,
                    fraction = 0.71f, timeLeft = "about 7 min left",
                ),
                lines, demoSamples(16.0),
            )
            "test-live" -> Jobs.demo(
                JobState.Running(
                    "Drive test", "Fanxiang F372S", Stage.WRITE, 600_000_000, 1_200_000_000,
                    fraction = 0.38f, local = true,
                ),
                lines, demoSamples(18.0),
            )
            "done" -> Jobs.demo(JobState.Finished(Outcome.OK, "", name, "SanDisk Ultra Fit"), lines)
            "done-ventoy" -> Jobs.demo(
                JobState.Finished(Outcome.OK, "1.1.18", VENTOY.name, "SanDisk Ultra Fit", setOf(ForgeService.FLAG_SB_ON)), lines,
            )
            "done-import" -> Jobs.demo(
                JobState.Finished(
                    Outcome.OK, "Nobara-44-Steam-Handheld.iso\nbazzite-deck-stable.iso", "2 images", "SanDisk Ultra Fit",
                    setOf(ForgeService.FLAG_IMPORT),
                ),
                lines,
            )
            "done-format" -> Jobs.demo(JobState.Finished(Outcome.OK, "ext4\nGAMES", FORMAT.name, "SanDisk Ultra Fit"), lines)
            "done-test" -> Jobs.demo(
                JobState.Finished(
                    Outcome.OK,
                    DriveTest.Report(
                        62_900_000_000, 1_073_741_824, listOf(48.0, 47.5, 9.2, 3.4), 21.3, 46.8, 0, 128, 0, null,
                    ).lines().joinToString("\n") + "\nConnected at USB 3 speed",
                    "Drive test", "Fanxiang F372S", setOf(ForgeService.FLAG_TEST),
                ),
                lines, demoSamples(40.0),
            )
            "done-cachyos" -> Jobs.demo(
                JobState.Finished(Outcome.OK, "", catalog.first { it.id == CACHY }.name, "SanDisk Ultra Fit"), lines,
            )
            "error" -> Jobs.demo(
                JobState.Finished(Outcome.ERROR, "This drive is write-protected. Check for a lock switch, or use another drive.", name, "SanDisk Ultra Fit"),
                lines,
            )
        }
    }

    @Composable
    private fun App(catalog: List<Distro>, demo: String?) {
        val job by Jobs.state.collectAsState()
        val tick by permissionTick.collectAsState()
        // The confirm demo shows CachyOS so its handheld warning is in the screenshot.
        val first = if (demo == "confirm") CACHY else catalog.firstOrNull { it.enabled && !it.hidden }?.id
        val labsOn by labs.collectAsState()
        val shown = catalog.filter { !it.hidden || labsOn }
        var distroId by rememberSaveable {
            mutableStateOf(
                when (demo) {
                    "file" -> ForgeService.CUSTOM_ID
                    "ventoy", "confirm-ventoy" -> ForgeService.VENTOY_ID
                    "labs", "confirm-labs" -> "batocera"
                    "confirm-format-ext4" -> ForgeService.FORMAT_ID
                    else -> first
                },
            )
        }
        var stickKey by rememberSaveable { mutableStateOf(if (demo != null && demo != "empty") "demo1" else null) }
        var confirming by rememberSaveable { mutableStateOf(demo == "confirm" || demo == "confirm-ventoy" || demo == "confirm-labs" || demo == "confirm-format-ext4") }
        var hint by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        var importing by rememberSaveable { mutableStateOf(demo == "import") }
        var labsTools by rememberSaveable { mutableStateOf(demo == "labs-tools") }
        var secureBoot by rememberSaveable { mutableStateOf(true) }
        // Format drive: the filesystem and name (the same choices as Raven Disk Utility).
        var formatFs by rememberSaveable { mutableStateOf(if (demo == "confirm-format-ext4") FsType.EXT4.name else FsType.EXFAT.name) }
        var formatLabel by rememberSaveable { mutableStateOf(if (demo == "confirm-format-ext4") "GAMES" else PlainDrive.LABEL) }
        var picked by remember {
            mutableStateOf(
                if (demo == "import") {
                    listOf(
                        Picked("d1", "Nobara-44-Steam-Handheld-2026-10-13.iso", 9_300_000_000, ImageKind.ISO_HYBRID),
                        Picked("d2", "steamdeck-repair-latest.img.bz2", 2_800_000_000, ImageKind.BZIP2),
                    )
                } else {
                    emptyList()
                },
            )
        }
        val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val found = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        try {
                            try {
                                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (_: Exception) {
                            }
                            val (n, size) = ForgeService.describeFile(this@MainActivity, uri)
                            Picked(uri.toString(), n, size, ForgeService.sniffFile(this@MainActivity, uri))
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                picked = (picked + found).distinctBy { it.uri }
                hint = if (found.size < uris.size) "Some files couldn't be read and were left out." else ""
            }
        }
        // A file the person picked to write instead of a listed system.
        var fileUri by rememberSaveable { mutableStateOf<String?>(if (demo == "file") "content://demo/new-os.img.bz2" else null) }
        var fileName by rememberSaveable { mutableStateOf(if (demo == "file") "new-os.img.bz2" else "") }
        var fileSize by rememberSaveable { mutableStateOf(if (demo == "file") 3_400_000_000L else -1L) }
        var fileKind by rememberSaveable { mutableStateOf(if (demo == "file") ImageKind.XZ.name else ImageKind.UNKNOWN.name) }
        val kind = ImageKind.valueOf(fileKind)
        val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // Not every file provider offers lasting access; this session's access is enough.
                }
                scope.launch {
                    val picked = withContext(Dispatchers.IO) {
                        try {
                            Triple(ForgeService.describeFile(this@MainActivity, uri), ForgeService.sniffFile(this@MainActivity, uri), null)
                        } catch (e: Exception) {
                            Triple(null, null, e.message ?: "Couldn't read that file")
                        }
                    }
                    val (desc, k, err) = picked
                    if (desc == null || k == null) {
                        hint = "Couldn't read that file: $err"
                        return@launch
                    }
                    fileUri = uri.toString()
                    fileName = desc.first
                    fileSize = desc.second
                    fileKind = k.name
                    distroId = ForgeService.CUSTOM_ID
                    hint = if (k.writable) "" else "${desc.first} is a ${k.label} file, which can't be written yet. Unpack it first."
                }
            }
        }
        var list by remember { mutableStateOf(if (demo != null && demo != "empty") demoSticks else emptyList()) }
        // Sticks Android has already been asked about this session, so it asks once per stick, not in a loop.
        val asked = remember { mutableSetOf<String>() }
        // What the person tapped while Android was still asking for the drive: done as soon as it says yes.
        var pending by remember { mutableStateOf<String?>(null) }
        var pendingAt by remember { mutableStateOf(0) }

        // Watch for sticks being plugged in or pulled out while choosing. A new stick gets
        // Android's access question right away, so it's answered before anything else.
        LaunchedEffect(job is JobState.Idle, tick) {
            while (job is JobState.Idle && demo == null) {
                val now = withContext(Dispatchers.IO) { sticks.list(probe = true) }
                list = now
                if (now.none { it.key == stickKey }) stickKey = now.firstOrNull { it.hasPermission }?.key ?: now.firstOrNull()?.key
                now.firstOrNull { !it.hasPermission && it.key !in asked }?.let { s ->
                    asked += s.key
                    stickKey = s.key
                    sticks.device(s.key)?.let { sticks.requestPermission(it) }
                }
                delay(2000)
            }
        }
        LaunchedEffect(job) {
            keepScreenOn(job is JobState.Running)
            // A finished job may have changed the drive (now Ventoy, now plain): look at it again.
            if (job is JobState.Finished && demo == null) sticks.forget()
        }

        // Hiding experimental options again shouldn't leave one of them picked.
        LaunchedEffect(labsOn) {
            if (!labsOn && catalog.any { it.id == distroId && it.hidden }) {
                distroId = first
                confirming = false
            }
        }

        val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val custom = distroId == ForgeService.CUSTOM_ID && fileUri != null
        val tool = TOOLS.firstOrNull { it.id == distroId }
        // A picked file runs as a one-off "system" named after the file.
        val distro = when {
            custom -> Distro(ForgeService.CUSTOM_ID, fileName, kind.label, true, 1, null, emptyList(), warning = kind.warning)
            tool != null -> tool
            else -> catalog.firstOrNull { it.id == distroId }
        }
        val stick = list.firstOrNull { it.key == stickKey }

        // Android answered the access question: carry on with what was tapped, or say it was refused.
        LaunchedEffect(tick, list) {
            val want = pending ?: return@LaunchedEffect
            val s = list.firstOrNull { it.key == stickKey } ?: return@LaunchedEffect
            if (s.hasPermission) {
                pending = null
                when (want) {
                    "ventoy" -> if (s.ventoy) importing = true
                    "create" -> {
                        val size = s.sizeBytes
                        when {
                            distro == null -> {}
                            !custom && size != null && size < distro.minDriveBytes ->
                                hint = "That drive is too small. ${distro.name} needs ${distro.minDriveGb} GB or more."
                            custom && size != null && !kind.compressed && fileSize > size -> hint = "That drive is too small for $fileName."
                            else -> confirming = true
                        }
                    }
                }
            } else if (tick > pendingAt && sticks.device(s.key)?.let { sticks.hasPermission(it) } == false) {
                pending = null
                hint = "Android didn't allow access to the drive. Tap the drive to ask again."
            }
        }


        BackHandler(enabled = confirming && job is JobState.Idle) { confirming = false }
        BackHandler(enabled = importing && !confirming && job is JobState.Idle) { importing = false }
        BackHandler(enabled = labsTools && job is JobState.Idle) { labsTools = false }

        Box(Modifier.fillMaxSize().background(Forge.Slate).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth()) {
                    Txt("Handheld Forge", Forge.brand)
                    Spacer(Modifier.weight(1f))
                    if (labsOn && job is JobState.Idle && !labsTools) {
                        Txt(
                            "Labs", Forge.brand.copy(color = Forge.MoltenA),
                            Modifier.clickable { labsTools = true; confirming = false; importing = false }.padding(horizontal = 6.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    val taps = remember { MutableInteractionSource() }
                    Txt(
                        "v${BuildConfigCompat.versionName(this@MainActivity)}", Forge.brand,
                        Modifier.clickable(interactionSource = taps, indication = null) { tapVersion() },
                    )
                }
                Gap(18)
                when (val j = job) {
                    is JobState.Running -> Working(j, askStopFirst = demo == "stop")
                    is JobState.Finished -> Result(
                        j, (catalog + TOOLS).firstOrNull { it.name == j.distro },
                        onAgain = {
                            Jobs.reset()
                            confirming = false
                            importing = false
                            if (ForgeService.FLAG_IMPORT in j.flags && j.outcome == Outcome.OK) picked = emptyList()
                        },
                        onImport = {
                            Jobs.reset()
                            confirming = false
                            if (ForgeService.FLAG_IMPORT in j.flags && j.outcome == Outcome.OK) picked = emptyList()
                            hint = ""
                            importing = true
                        },
                    )
                    JobState.Idle -> if (labsTools && labsOn) {
                        val checkName = when {
                            custom -> fileName
                            tool != null -> null
                            else -> distro?.takeIf { it.enabled }?.name
                        }
                        LabsTools(
                            stick, checkName, demo,
                            onTest = { s -> ForgeService.startTest(this@MainActivity, s.key, s.label) },
                            onCheck = { s ->
                                ForgeService.startCheck(
                                    this@MainActivity, checkName ?: "", if (custom) null else distro?.id,
                                    if (custom) Uri.parse(fileUri) else null, s.key, s.label,
                                )
                            },
                            onBack = { labsTools = false },
                        )
                    } else if (importing && stick != null && !confirming) {
                        VentoyFiles(
                            stick, picked, hint, demo,
                            onPick = { pickImages.launch(arrayOf("*/*")) },
                            onUnpick = { p -> picked = picked - p },
                            onBack = { importing = false },
                            onCopy = {
                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                ForgeService.startImport(this@MainActivity, picked.map { Uri.parse(it.uri) }, stick.key, stick.label)
                            },
                        )
                    } else if (confirming && distro != null && stick != null) {
                        Confirm(
                            distro, stick, needsInternet = !custom && distroId != ForgeService.FORMAT_ID,
                            secureBoot = secureBoot, onSecureBoot = { secureBoot = it }, onBack = { confirming = false },
                            formatFs = FsType.valueOf(formatFs), onFormatFs = { formatFs = it.name },
                            formatLabel = formatLabel, onFormatLabel = { formatLabel = it },
                        ) {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            if (custom) {
                                ForgeService.startFile(this@MainActivity, Uri.parse(fileUri), fileName, stick.key, stick.label)
                            } else if (tool != null) {
                                ForgeService.startTool(
                                    this@MainActivity, tool.id, tool.name, stick.key, stick.label, secureBoot,
                                    FsType.valueOf(formatFs), formatLabel,
                                )
                            } else {
                                ForgeService.start(this@MainActivity, distro, stick.key, stick.label)
                            }
                        }
                    } else {
                        Choose(
                            shown, distroId, fileName.takeIf { fileUri != null }, fileSize, kind, list, stickKey,
                            if (pending != null) "Waiting for Android to allow access to the drive..." else hint,
                            onDistro = { distroId = it },
                            onPickFile = { pickFile.launch(ForgeService.PICK_TYPES) },
                            onAddToVentoy = {
                                hint = ""
                                if (stick?.hasPermission == true) {
                                    importing = true
                                } else {
                                    pending = "ventoy"
                                    pendingAt = tick
                                    stick?.let { s -> sticks.device(s.key)?.let { sticks.requestPermission(it) } }
                                }
                            },
                            onStick = { s ->
                                stickKey = s.key
                                hint = ""
                                if (!s.hasPermission) sticks.device(s.key)?.let { sticks.requestPermission(it) }
                            },
                            onCreate = {
                                val size = stick?.sizeBytes
                                when {
                                    distro == null || stick == null -> {}
                                    !stick.hasPermission -> {
                                        pending = "create"
                                        pendingAt = tick
                                        sticks.device(stick.key)?.let { sticks.requestPermission(it) }
                                    }
                                    !custom && size != null && size < distro.minDriveBytes ->
                                        hint = "That drive is too small. ${distro.name} needs ${distro.minDriveGb} GB or more."
                                    custom && !kind.writable ->
                                        hint = "$fileName is a ${kind.label} file, which can't be written yet. Unpack it first."
                                    custom && size != null && !kind.compressed && fileSize > size ->
                                        hint = "That drive is too small for $fileName."
                                    else -> confirming = true
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.Choose(
        catalog: List<Distro>,
        distroId: String?,
        pickedName: String?,
        pickedSize: Long,
        pickedKind: ImageKind,
        list: List<Stick>,
        stickKey: String?,
        hint: String,
        onDistro: (String) -> Unit,
        onPickFile: () -> Unit,
        onAddToVentoy: () -> Unit,
        onStick: (Stick) -> Unit,
        onCreate: () -> Unit,
    ) {
        Txt("Pick a system and a USB drive.", Forge.title)
        val fileChosen = distroId == ForgeService.CUSTOM_ID && pickedName != null
        Gap(18)
        Row(horizontalArrangement = RowGap) {
            catalog.filter { !it.hidden }.forEach { d ->
                Tile(
                    d.name, d.edition, selected = d.id == distroId, enabled = d.enabled,
                    tag = if (d.enabled) "" else "Soon", subtitleLines = 2, modifier = Modifier.weight(1f),
                ) { onDistro(d.id) }
            }
        }
        // Experimental systems, once unlocked: a full-width row each, tagged so they read as such.
        catalog.filter { it.hidden }.forEach { d ->
            Gap(10)
            Tile(
                d.name, d.edition, selected = d.id == distroId, enabled = d.enabled,
                tag = "Labs", modifier = Modifier.fillMaxWidth(),
            ) { onDistro(d.id) }
        }
        Gap(10)
        // Anything else: an ISO or disk image already on the phone, for systems not listed here yet.
        Tile(
            pickedName ?: "Your own file",
            if (pickedName == null) "Pick an ISO or disk image on this phone"
            else listOfNotNull(pickedKind.label, if (pickedSize > 0) gb(pickedSize) else null).joinToString(", "),
            selected = fileChosen, tag = if (fileChosen) "Change" else "", modifier = Modifier.fillMaxWidth(),
        ) { if (fileChosen || pickedName == null) onPickFile() else onDistro(ForgeService.CUSTOM_ID) }
        Gap(10)
        Row(horizontalArrangement = RowGap) {
            Tile(VENTOY.name, "Multi-boot: many images, one drive", selected = distroId == VENTOY.id, subtitleLines = 2, modifier = Modifier.weight(1f)) {
                onDistro(VENTOY.id)
            }
            Tile(FORMAT.name, "Empty exFAT drive for files", selected = distroId == FORMAT.id, subtitleLines = 2, modifier = Modifier.weight(1f)) {
                onDistro(FORMAT.id)
            }
        }
        Gap(14)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = RowGap) {
            if (list.isEmpty()) {
                EmptySlot("Plug in a USB drive of 16 GB or more with a USB-C adapter")
            }
            list.forEach { s ->
                val sub = when {
                    !s.hasPermission -> "Tap to allow access"
                    s.sizeBytes != null -> gb(s.sizeBytes) + if (s.ventoy) ", has Ventoy" else ""
                    else -> "Reading size"
                }
                Tile(s.label, sub, selected = s.key == stickKey, usb = true, modifier = Modifier.fillMaxWidth()) { onStick(s) }
            }
        }
        // A Ventoy drive can take more images without being erased.
        list.firstOrNull { it.key == stickKey && it.ventoy }?.let {
            Gap(10)
            Row(
                Modifier.fillMaxWidth().background(Forge.Iron, RoundedCornerShape(14.dp)).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("This drive has Ventoy. Add OS images to it without erasing anything.", Forge.hint, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                QuietButton("Add images", onClick = onAddToVentoy)
            }
        }
        Gap(10)
        if (hint.isNotEmpty()) {
            Txt(hint, Forge.hint.copy(color = Forge.Danger))
            Gap(10)
        }
        val isTool = TOOLS.any { it.id == distroId }
        val ready = ((fileChosen && pickedKind.writable) || isTool || catalog.any { it.id == distroId && it.enabled }) &&
            list.any { it.key == stickKey }
        MoltenButton(
            when {
                fileChosen -> "Write to drive"
                distroId == VENTOY.id -> "Install Ventoy"
                distroId == FORMAT.id -> "Format drive"
                else -> "Create boot drive"
            }, enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = onCreate)
    }

    @Composable
    private fun ColumnScope.Confirm(
        distro: Distro,
        stick: Stick,
        needsInternet: Boolean,
        secureBoot: Boolean,
        onSecureBoot: (Boolean) -> Unit,
        onBack: () -> Unit,
        formatFs: FsType = FsType.EXFAT,
        onFormatFs: (FsType) -> Unit = {},
        formatLabel: String = "",
        onFormatLabel: (String) -> Unit = {},
        onErase: () -> Unit,
    ) {
        val resumed by resumeTick.collectAsState()
        var phone by remember { mutableStateOf(phoneState()) }
        // Battery and network change while the person reads; keep it current.
        LaunchedEffect(resumed) {
            while (true) {
                phone = phoneState()
                delay(2000)
            }
        }
        val checks = Preflight.checks(phone, download = needsInternet)
        val problems = checks.filter { !it.ok }

        Txt("Erase this drive?", Forge.title)
        Gap(8)
        Txt(
            when (distro.id) {
                ForgeService.FORMAT_ID -> "Everything on it will be erased, leaving one empty drive. Pick what you'll use it with."
                ForgeService.VENTOY_ID -> "Everything on it will be replaced with the latest Ventoy. Afterwards add images with this app (or from a computer) and choose one when you start from it."
                else -> "Everything on it will be replaced with ${distro.name}."
            },
            Forge.hint,
        )
        Gap(14)
        Tile(stick.label, stick.sizeBytes?.let { gb(it) } ?: "", selected = true, usb = true, modifier = Modifier.fillMaxWidth())
        Gap(16)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (distro.id == ForgeService.FORMAT_ID) {
                FormatChoices(formatFs, onFormatFs, formatLabel, onFormatLabel)
                Gap(14)
            }
            if (distro.id == ForgeService.VENTOY_ID) {
                Txt("Secure Boot support", Forge.brand)
                Gap(8)
                Row(horizontalArrangement = RowGap) {
                    Tile("On", "Recommended. Enroll a key once", selected = secureBoot, subtitleLines = 2, modifier = Modifier.weight(1f)) {
                        onSecureBoot(true)
                    }
                    Tile("Off", "Needs Secure Boot off in BIOS", selected = !secureBoot, subtitleLines = 2, modifier = Modifier.weight(1f)) {
                        onSecureBoot(false)
                    }
                }
                Gap(8)
                Txt(
                    if (secureBoot) {
                        "Starts whether Secure Boot is on or off. On a handheld with Secure Boot on, the first start asks you to enroll Ventoy's key once."
                    } else {
                        "No key step, but the drive only starts once Secure Boot is turned off in the BIOS. The Steam Deck doesn't use Secure Boot, so either works there."
                    },
                    Forge.hint,
                )
                Gap(14)
            }
            distro.warning?.let {
                Banner(it)
                Gap(14)
            }
            // Only what needs attention; nothing at all when the phone is ready.
            problems.forEach { c ->
                CheckRow(c.title, c.detail, c.ok, c.required, onClick = c.fix?.let { f -> { fix(f) } })
            }
        }
        Gap(16)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietButton("Go back", onClick = onBack)
            MoltenButton(
                when (distro.id) {
                    ForgeService.FORMAT_ID -> "Erase and format"
                    ForgeService.VENTOY_ID -> "Erase and install"
                    else -> "Erase and write"
                }, enabled = Preflight.canStart(checks) &&
                    (distro.id != ForgeService.FORMAT_ID || FsSupport.labelProblem(formatFs, formatLabel.trim()) == null),
                modifier = Modifier.weight(1f), onClick = onErase,
            )
        }
    }

    /** Format drive choices, the same as Raven Disk Utility's: what it's for, and an optional name. */
    @Composable
    private fun FormatChoices(fs: FsType, onFs: (FsType) -> Unit, label: String, onLabel: (String) -> Unit) {
        Txt("Use it with", Forge.brand)
        Gap(8)
        listOf(
            Triple(FsType.EXFAT, "exFAT", "Windows, Mac, Linux, Android, cameras and consoles. Best for most drives"),
            Triple(FsType.FAT32, "FAT32", "Older gear: car stereos, some TVs and consoles. Files up to 4 GB"),
            Triple(FsType.EXT4, "ext4", "Linux: Steam Deck, Bazzite, SteamOS game drives. Windows and Mac can't read it"),
        ).forEach { (t, name, detail) ->
            Tile(name, detail, selected = fs == t, subtitleLines = 2, modifier = Modifier.fillMaxWidth()) { onFs(t) }
            Gap(8)
        }
        Gap(6)
        Txt("Name (optional)", Forge.brand)
        Gap(8)
        val shape = RoundedCornerShape(14.dp)
        androidx.compose.foundation.text.BasicTextField(
            value = label,
            onValueChange = { v -> onLabel(v.take(fs.maxLabel)) },
            singleLine = true,
            textStyle = TextStyle(color = Forge.Bone, fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Forge.MoltenA),
            modifier = Modifier.fillMaxWidth().clip(shape).background(Forge.Iron).border(1.dp, Forge.Seam, shape).padding(16.dp),
            decorationBox = { inner ->
                if (label.isEmpty()) Txt("My drive", TextStyle(color = Forge.Ash, fontSize = 16.sp))
                inner()
            },
        )
        FsSupport.labelProblem(fs, label.trim())?.let {
            Gap(6)
            Txt(it, Forge.hint.copy(color = Forge.Danger, fontSize = 13.sp))
        }
    }

    @Composable
    private fun ColumnScope.Working(j: JobState.Running, askStopFirst: Boolean = false) {
        val step = stepOf(j.stage)
        // The bar glides toward the job's overall progress, which only ever grows: 0 to 100, left to right.
        val overall by animateFloatAsState(j.fraction, tween(700, easing = LinearEasing), label = "overall")
        // Ticks every second so timers keep moving during steps that can't measure themselves.
        var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                now = SystemClock.elapsedRealtime()
            }
        }
        var showLog by remember { mutableStateOf(false) }
        var backWarned by remember { mutableStateOf(false) }
        var askStop by remember { mutableStateOf(askStopFirst) }
        val log by Jobs.log.collectAsState()
        // Back would close the screen mid-job; warn first, let a second press through.
        BackHandler(enabled = !backWarned) { backWarned = true }
        // While the stop question is showing, back just dismisses it.
        BackHandler(enabled = askStop) { askStop = false }

        Txt(
            when {
                j.distro == FORMAT.name -> "Formatting ${j.drive}"
                j.distro == VENTOY.name -> "Installing Ventoy on ${j.drive}"
                j.local -> "Writing ${j.distro} to ${j.drive}"
                else -> "Creating ${j.distro} on ${j.drive}"
            },
            Forge.title,
        )
        Gap(12)
        Banner(
            if (backWarned) {
                "Press back again to leave. The job keeps going, but Android may pause it while you're away, " +
                    "which can stop the download or ruin the drive. Staying here is safest."
            } else {
                "Leave the phone alone until this finishes. Keep this screen open, don't switch apps or lock the phone, " +
                    "and don't unplug the drive."
            },
        )
        Spacer(Modifier.weight(1f))
        Txt("${(j.fraction * 100).toInt().coerceIn(0, 100)}%", Forge.big)
        HeatBar(overall, active = true)
        Gap(12)
        val (headline, detail) = Progress.describe(j.stage, j.done, j.total, now - j.stageStartMs, j.distro, j.timeLeft, j.local)
        Txt(headline, Forge.status)
        Gap(4)
        Txt(detail, Forge.hint)
        log.lastOrNull()?.let {
            Gap(8)
            // The latest thing that happened, without its timestamp.
            Txt(it.substringAfter("  "), Forge.hint, maxLines = 2)
        }
        val samples by Jobs.samples.collectAsState()
        if (samples.isNotEmpty()) {
            Gap(14)
            SpeedGraph(samples, live = true)
        }
        Spacer(Modifier.weight(1f))
        val first = Progress.firstStep(j.local)
        StepTrack(STEPS.drop(first), step - first, failed = false)
        Gap(18)
        if (askStop && !j.stopping) {
            // Stopping mid-write leaves the drive unbootable, so make it a deliberate second tap.
            Banner(
                if (j.stage >= Stage.WRITE) {
                    "Stop now? The drive is only partly written and won't boot until you create it again."
                } else {
                    "Stop now? The download pauses and picks up where it left off next time."
                },
            )
            Gap(12)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuietButton("Keep going") { askStop = false }
                MoltenButton("Stop", danger = true, modifier = Modifier.weight(1f)) {
                    askStop = false
                    Jobs.cancel()
                }
            }
        } else {
            Row {
                QuietButton(if (showLog) "Hide details" else "Details") { showLog = !showLog }
                Spacer(Modifier.weight(1f))
                QuietButton(if (j.stopping) "Stopping" else "Stop", enabled = !j.stopping) { askStop = true }
            }
        }
        if (showLog) LogBox(log)
    }

    /** Labs: experimental settings and drive tools. */
    @Composable
    private fun ColumnScope.LabsTools(
        stick: Stick?,
        checkName: String?,
        demo: String?,
        onTest: (Stick) -> Unit,
        onCheck: (Stick) -> Unit,
        onBack: () -> Unit,
    ) {
        var stream by remember { mutableStateOf(Labs.streamWriteSetting(this@MainActivity)) }
        var skip by remember { mutableStateOf(Labs.skipCheckSetting(this@MainActivity)) }
        var askTest by remember { mutableStateOf(false) }
        val usable = stick?.takeIf { it.hasPermission }
        Txt("Labs", Forge.title)
        Gap(8)
        Txt("Experimental settings and tools. They work, but they've had less testing than the rest of the app.", Forge.hint)
        Gap(16)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Txt("Settings", Forge.brand)
            Gap(8)
            Toggle(
                "Write while downloading",
                "Starts writing as the download comes in, so most of the download time disappears. The download is still checked at the end; if it fails, make the drive again.",
                stream,
            ) {
                stream = it
                if (demo == null) Labs.setStreamWrite(this@MainActivity, it)
            }
            Gap(10)
            Toggle(
                "Skip the read-back check",
                "Saves close to half the time after writing. A fake or failing drive won't be caught, so only use it with a drive you trust.",
                skip,
            ) {
                skip = it
                if (demo == null) Labs.setSkipCheck(this@MainActivity, it)
            }
            Gap(20)
            Txt("Tools", Forge.brand)
            Gap(8)
            ToolCard(
                "Test a drive",
                (usable?.let { "${it.label}: " } ?: "") +
                    "real write and read speed, and whether it really holds what it claims. Takes 1 to 6 minutes. " +
                    "Erases the drive, then formats it so it's usable again.",
                warn = if (askTest) "Tap again to erase ${usable?.label ?: "the drive"} and test it." else null,
                action = if (askTest) "Erase and test" else "Test",
                enabled = usable != null,
            ) {
                if (!askTest) askTest = true else { askTest = false; usable?.let(onTest) }
            }
            Gap(10)
            ToolCard(
                "Check a drive",
                when {
                    checkName == null -> "Pick a system or your own file on the main screen first, then compare a drive with it."
                    else -> (usable?.let { "Compare ${it.label} with $checkName" } ?: "Compare a drive with $checkName") +
                        ", byte for byte, without changing anything. Handy when a handheld won't start from it."
                },
                warn = null,
                action = "Check",
                enabled = usable != null && checkName != null,
            ) { usable?.let(onCheck) }
            if (usable == null) {
                Gap(10)
                Txt(if (stick == null) "Plug in a USB drive to use the tools." else "Allow access to the drive on the main screen first.", Forge.hint)
            }
        }
        Gap(14)
        QuietButton("Back", modifier = Modifier.fillMaxWidth(), onClick = onBack)
    }

    @Composable
    private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(Forge.Iron).clickable { onChange(!on) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(title, TextStyle(color = Forge.Bone, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                Gap(4)
                Txt(detail, Forge.hint.copy(fontSize = 13.sp, lineHeight = 18.sp))
            }
            Spacer(Modifier.width(12.dp))
            val track = RoundedCornerShape(50)
            Box(
                Modifier.width(48.dp).height(28.dp).clip(track)
                    .then(if (on) Modifier.background(Forge.molten) else Modifier.background(Forge.Seam))
                    .padding(3.dp),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.size(22.dp).clip(track).background(if (on) Forge.Ink else Forge.Ash))
            }
        }
    }

    @Composable
    private fun ToolCard(title: String, detail: String, warn: String?, action: String, enabled: Boolean, onAction: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().background(Forge.Iron, RoundedCornerShape(14.dp)).padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(title, TextStyle(color = Forge.Bone, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                Gap(4)
                Txt(detail, Forge.hint.copy(fontSize = 13.sp, lineHeight = 18.sp))
                warn?.let {
                    Gap(6)
                    Txt(it, Forge.hint.copy(fontSize = 13.sp, color = Forge.MoltenA))
                }
            }
            Spacer(Modifier.width(10.dp))
            QuietButton(action, enabled = enabled, onClick = onAction)
        }
    }

    @Composable
    private fun ColumnScope.Result(j: JobState.Finished, distro: Distro?, onAgain: () -> Unit, onImport: () -> Unit) {
        var showLog by remember { mutableStateOf(false) }
        val log by Jobs.log.collectAsState()
        val imported = ForgeService.FLAG_IMPORT in j.flags
        val tested = ForgeService.FLAG_TEST in j.flags
        val checked = ForgeService.FLAG_CHECK in j.flags
        val ventoy = distro?.id == ForgeService.VENTOY_ID
        val ok = j.outcome == Outcome.OK
        val steps = when {
            !ok || imported || tested || checked -> emptyList()
            ventoy -> ventoySteps(ForgeService.FLAG_SB_OFF !in j.flags)
            else -> distro?.installSteps.orEmpty()
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (steps.isEmpty()) Gap(60)
            StatusMark(
                when (j.outcome) {
                    Outcome.OK -> "ok"
                    Outcome.ERROR -> "error"
                    Outcome.CANCELLED -> "cancelled"
                },
            )
            Gap(16)
            Txt(
                when (j.outcome) {
                    Outcome.OK -> when {
                        tested && ForgeService.FLAG_TEST_BAD in j.flags -> "This drive failed the test"
                        tested -> "Drive test results"
                        checked -> "The drive matches"
                        imported -> "Added to Ventoy"
                        distro?.id == ForgeService.FORMAT_ID -> "Drive formatted"
                        ventoy -> "Ventoy is installed"
                        else -> "Ready to boot"
                    }
                    Outcome.CANCELLED -> "Stopped"
                    Outcome.ERROR -> when {
                        imported -> "The images weren't all added"
                        tested -> "The test didn't finish"
                        checked -> "The drive doesn't match"
                        else -> "The drive wasn't finished"
                    }
                },
                Forge.title,
            )
            Gap(8)
            Txt(
                when {
                    ok && tested -> "${j.drive}, measured. It's been formatted again, so it's ready to use:"
                    ok && checked -> j.message
                    ok && imported -> "These are on ${j.drive} and will show in Ventoy's menu:"
                    ok && distro?.id == ForgeService.FORMAT_ID -> {
                        val fsName = j.message.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "exFAT"
                        val named = j.message.lineSequence().drop(1).firstOrNull()?.takeIf { it.isNotBlank() }
                        "${j.drive} is now one empty $fsName drive" + (named?.let { " named \"$it\"" } ?: "") +
                            ". Unplug it and plug it back in to use it."
                    }
                    ok && ventoy -> "${j.drive} now has Ventoy ${j.message}, " +
                        (if (ForgeService.FLAG_SB_OFF in j.flags) "without" else "with") + " Secure Boot support."
                    ok -> "${j.drive} now holds ${j.distro}. " + if (steps.isEmpty()) {
                        "Plug it into your handheld, open the boot menu and choose the USB drive."
                    } else {
                        "Plug it into your handheld and follow these steps."
                    }
                    else -> j.message
                },
                Forge.hint,
            )
            if (ok && (imported || tested)) {
                Gap(12)
                j.message.lines().filter { it.isNotBlank() }.forEach {
                    Gap(6)
                    Txt(it, TextStyle(color = Forge.Bone, fontSize = 15.sp, lineHeight = 20.sp))
                }
                if (imported) {
                    Gap(18)
                    Txt("Plug the drive into your handheld, start it from USB and pick one from Ventoy's menu.", Forge.hint)
                }
            }
            val samples by Jobs.samples.collectAsState()
            if (samples.isNotEmpty()) {
                Gap(18)
                Txt("Speed", Forge.brand)
                Gap(8)
                SpeedGraph(samples, live = false)
            }
            if (steps.isNotEmpty()) InstallGuide(steps, ventoy = ventoy)
        }
        Gap(12)
        if (showLog) {
            LogBox(log)
            // Everything up to now, for a bug report: copy it, or open a prefilled GitHub issue.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuietButton("Copy report") { copyReport(reportInfo(j, distro, log)) }
                QuietButton("Report a bug") {
                    val info = reportInfo(j, distro, log)
                    copyReport(info)
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(io.github.ravenor907.handheldforge.core.BugReport.issueUrl(info))))
                    }
                }
            }
            Gap(10)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietButton("Details") { showLog = !showLog }
            if (ok && (ventoy || imported)) {
                QuietButton("Done", onClick = onAgain)
                MoltenButton(if (imported) "Add more" else "Add images", modifier = Modifier.weight(1f), onClick = onImport)
            } else if (imported) {
                MoltenButton("Back to the drive", modifier = Modifier.weight(1f), onClick = onImport)
            } else if (tested || checked) {
                MoltenButton("Done", modifier = Modifier.weight(1f), onClick = onAgain)
            } else {
                MoltenButton(
                    when (j.outcome) {
                        Outcome.OK -> "Make another"
                        Outcome.CANCELLED -> "Start over"
                        Outcome.ERROR -> "Try again"
                    },
                    modifier = Modifier.weight(1f),
                    onClick = onAgain,
                )
            }
        }
    }

    /** What's on a Ventoy drive, and the images picked to add to it. */
    @Composable
    private fun ColumnScope.VentoyFiles(
        stick: Stick,
        picked: List<Picked>,
        hint: String,
        demo: String?,
        onPick: () -> Unit,
        onUnpick: (Picked) -> Unit,
        onBack: () -> Unit,
        onCopy: () -> Unit,
    ) {
        var info by remember { mutableStateOf<VentoyInfo?>(null) }
        var error by remember { mutableStateOf("") }
        var removeError by remember { mutableStateOf("") }
        var reload by remember { mutableStateOf(0) }
        var confirmRemove by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun <T> withVolume(op: (io.github.ravenor907.handheldforge.core.VentoyDrive, io.github.ravenor907.handheldforge.core.ExFatVolume, io.github.ravenor907.ravendisk.core.io.BlockDevice) -> T): T {
            val d = sticks.device(stick.key) ?: throw java.io.IOException("The drive was unplugged.")
            return sticks.open(d).use { s ->
                val vd = io.github.ravenor907.handheldforge.core.VentoyDrive.detect(s.disk)
                    ?: throw java.io.IOException("This drive doesn't have Ventoy on it.")
                op(vd, vd.openData(s.disk), s.disk)
            }
        }

        LaunchedEffect(stick.key, reload) {
            if (demo != null) {
                info = VentoyInfo(
                    "1.1.18", true, 21_400_000_000, 21_400_000_000,
                    listOf("bazzite-deck-stable.iso" to 8_300_000_000, "SteamOS-recovery.img" to 2_800_000_000),
                    bootNext = "bazzite-deck-stable.iso",
                )
                return@LaunchedEffect
            }
            busy = true
            try {
                info = withContext(Dispatchers.IO) {
                    withVolume { vd, vol, disk ->
                        val efi = vd.readEfi(disk)
                        val config = runCatching { VentoyConfig.bootNext(vol) }
                        VentoyInfo(
                            io.github.ravenor907.handheldforge.core.Ventoy.versionOf(efi),
                            io.github.ravenor907.handheldforge.core.Ventoy.secureBootEnabled(efi),
                            vol.freeBytes, vol.largestFreeRun(),
                            vol.files().sortedBy { it.name.lowercase() }.map { it.name to it.size },
                            bootNext = config.getOrNull(),
                            configError = config.exceptionOrNull()?.let { it.message ?: "ventoy.json couldn't be read" },
                        )
                    }
                }
                error = ""
            } catch (e: Exception) {
                info = null
                error = e.message ?: "Couldn't read the drive"
            }
            busy = false
        }

        Txt("Add images to Ventoy", Forge.title)
        Gap(8)
        Txt(
            info?.let {
                "${stick.label}: Ventoy ${it.version ?: ""}".trimEnd() + ", Secure Boot support " + (if (it.secureBoot) "on" else "off") +
                    ". ${gb(it.free)} free."
            } ?: if (error.isNotEmpty()) error else "Reading the drive...",
            if (error.isNotEmpty() && info == null) Forge.hint.copy(color = Forge.Danger) else Forge.hint,
        )
        if (removeError.isNotEmpty()) {
            Gap(6)
            Txt(removeError, Forge.hint.copy(color = Forge.Danger))
        }
        Gap(14)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Txt("To add", Forge.brand)
            if (picked.isEmpty()) {
                Gap(8)
                EmptySlot("Pick ISOs or disk images on this phone. Compressed ones are unpacked on the way in.")
            }
            picked.forEach { p ->
                Gap(8)
                val notes = buildList {
                    add(p.kind.label)
                    if (p.size > 0) add(gb(p.size))
                    if (p.kind.compressed) add("unpacked onto the drive")
                    if (!p.kind.writable) add("can't be unpacked here")
                }
                FileRow(p.name, notes.joinToString(", "), warn = p.kind.warning ?: if (!p.kind.writable) "Unpack it first, then pick the unpacked file." else null, action = "Remove") {
                    onUnpick(p)
                }
            }
            Gap(18)
            Txt("On the drive", Forge.brand)
            val files = info?.files.orEmpty()
            val bootNext = info?.bootNext
            if (info != null && files.isEmpty()) {
                Gap(8)
                Txt("Nothing yet.", Forge.hint)
            } else if (info != null) {
                Gap(4)
                Txt(
                    info?.configError ?: if (bootNext != null) {
                        "Ventoy starts the marked image on its own after ${VentoyConfig.TIMEOUT_SECONDS} seconds. Tap it again to go back to choosing from the menu."
                    } else {
                        "Tap an image to have Ventoy start it on its own, no buttons needed."
                    },
                    Forge.hint.copy(fontSize = 13.sp, color = if (info?.configError != null) Forge.Danger else Forge.hint.color),
                )
            }
            fun setBootNext(name: String?) {
                busy = true
                removeError = ""
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { withVolume { _, vol, _ -> VentoyConfig.setBootNext(vol, name) } }
                    } catch (e: Exception) {
                        removeError = "Couldn't change what starts on its own: ${e.message}"
                    }
                    reload++
                }
            }
            files.forEach { (name, size) ->
                Gap(8)
                val asking = confirmRemove == name
                val auto = name == bootNext
                val bootable = name.substringAfterLast('.', "").lowercase() in VentoyImport.BOOTABLE
                FileRow(
                    name,
                    gb(size) + if (auto) ", starts on its own" else "",
                    warn = if (asking) "Tap Remove again to delete it from the drive." else null,
                    action = if (asking) "Remove?" else "Remove",
                    selected = auto,
                    onClick = if (bootable && info?.configError == null && !busy && demo == null) {
                        { setBootNext(if (auto) null else name) }
                    } else {
                        null
                    },
                ) {
                    if (!asking) {
                        confirmRemove = name
                    } else {
                        confirmRemove = null
                        removeError = ""
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    withVolume { _, vol, _ ->
                                        vol.find(name)?.let { vol.delete(it) }
                                        // Don't leave Ventoy pointed at an image that's gone.
                                        if (name == bootNext) runCatching { VentoyConfig.setBootNext(vol, null) }
                                    }
                                }
                            } catch (e: Exception) {
                                removeError = "Couldn't remove $name: ${e.message}"
                            }
                            reload++
                        }
                    }
                }
            }
        }
        val known = picked.filter { !it.kind.compressed }.sumOf { it.size }
        val space = info?.let { i ->
            val tooBig = picked.firstOrNull { !it.kind.compressed && it.size > i.largest }
            when {
                known > i.free -> "That's more than the ${gb(i.free)} free on the drive."
                tooBig != null -> "${tooBig.name} needs ${gb(tooBig.size)} in one piece; the biggest free piece on the drive is ${gb(i.largest)}."
                else -> null
            }
        }
        (space ?: hint.takeIf { it.isNotEmpty() })?.let {
            Gap(10)
            Txt(it, Forge.hint.copy(color = Forge.Danger))
        }
        Gap(14)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietButton("Back", onClick = onBack)
            if (picked.isEmpty()) {
                MoltenButton("Pick images", enabled = info != null && !busy, modifier = Modifier.weight(1f), onClick = onPick)
            } else {
                QuietButton("Pick more", enabled = !busy, onClick = onPick)
                MoltenButton(
                    if (picked.size == 1) "Copy to drive" else "Copy ${picked.size} to drive",
                    enabled = info != null && !busy && space == null && picked.all { it.kind.writable },
                    modifier = Modifier.weight(1f),
                    onClick = onCopy,
                )
            }
        }
    }

    @Composable
    private fun FileRow(
        name: String,
        detail: String,
        warn: String?,
        action: String,
        selected: Boolean = false,
        onClick: (() -> Unit)? = null,
        onAction: () -> Unit,
    ) {
        val shape = RoundedCornerShape(14.dp)
        var m = Modifier.fillMaxWidth().background(Forge.Iron, shape)
        if (selected) m = m.border(2.dp, Forge.molten, shape)
        if (onClick != null) m = m.clip(shape).clickable(onClick = onClick)
        Row(
            m.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(name, TextStyle(color = Forge.Bone, fontSize = 16.sp, fontWeight = FontWeight.SemiBold), maxLines = 2)
                Gap(2)
                Txt(detail, Forge.hint.copy(fontSize = 13.sp))
                warn?.let {
                    Gap(4)
                    Txt(it, Forge.hint.copy(fontSize = 13.sp, color = Forge.MoltenA))
                }
            }
            Spacer(Modifier.width(8.dp))
            QuietButton(action, onClick = onAction)
        }
    }

    /** Opening the boot menu, then the system's own install steps. */
    @Composable
    private fun InstallGuide(steps: List<String>, ventoy: Boolean = false) {
        val body = TextStyle(color = Forge.Bone, fontSize = 14.sp, lineHeight = 20.sp)
        if (ventoy) {
            Gap(22)
            Txt("How to use it", Forge.brand)
            steps.forEachIndexed { i, s ->
                Gap(8)
                Row {
                    Txt("${i + 1}.", body.copy(color = Forge.MoltenA), Modifier.width(24.dp))
                    Txt(s, body, Modifier.weight(1f))
                }
            }
            // The general boot-menu help, minus lines about a particular system.
            val menu = bootMenu.filterNot { it.contains("Bazzite") || it.contains("Secure Boot") }
            if (menu.isNotEmpty()) {
                Gap(22)
                Txt("Starting from USB", Forge.brand)
                menu.forEach {
                    Gap(8)
                    Txt(it, body)
                }
            }
            return
        }
        if (bootMenu.isNotEmpty()) {
            Gap(22)
            Txt("Open the boot menu", Forge.brand)
            bootMenu.forEach {
                Gap(8)
                Txt(it, body)
            }
        }
        Gap(22)
        Txt("Then install", Forge.brand)
        steps.forEachIndexed { i, s ->
            Gap(8)
            Row {
                Txt("${i + 1}.", body.copy(color = Forge.MoltenA), Modifier.width(24.dp))
                Txt(s, body, Modifier.weight(1f))
            }
        }
    }

    private fun reportInfo(j: JobState.Finished, distro: Distro?, log: List<String>) =
        io.github.ravenor907.handheldforge.core.BugReport.Info(
            appVersion = BuildConfigCompat.versionName(this),
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            android = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            labs = if (Labs.isOn(this)) {
                mapOf("write while downloading" to Labs.streamWriteSetting(this), "skip read-back" to Labs.skipCheckSetting(this))
            } else {
                emptyMap()
            },
            job = distro?.name ?: j.distro,
            drive = j.drive,
            outcome = j.outcome.name,
            message = j.message,
            flags = j.flags,
            log = log,
        )

    private fun copyReport(info: io.github.ravenor907.handheldforge.core.BugReport.Info) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Handheld Forge report", io.github.ravenor907.handheldforge.core.BugReport.text(info)))
        android.widget.Toast.makeText(this, "Report copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun LogBox(lines: List<String>) {
        Gap(10)
        Column(
            Modifier.fillMaxWidth().heightIn(max = 160.dp)
                .background(Forge.Iron, RoundedCornerShape(10.dp)).padding(10.dp)
                .verticalScroll(rememberScrollState(Int.MAX_VALUE)),
        ) {
            Txt(lines.joinToString("\n"), TextStyle(color = Forge.Ash, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
        }
        Gap(10)
    }
}
