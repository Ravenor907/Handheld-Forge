package io.github.ravenor907.handheldforge

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ravenor907.handheldforge.core.Distro
import io.github.ravenor907.handheldforge.core.Fix
import io.github.ravenor907.handheldforge.core.ImageKind
import io.github.ravenor907.handheldforge.core.PhoneState
import io.github.ravenor907.handheldforge.core.Preflight
import io.github.ravenor907.handheldforge.core.Progress
import io.github.ravenor907.handheldforge.core.Stage
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
        Fix.BACKGROUND -> try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            // Some phones hide the one-tap prompt; the full list still works.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private val demoSticks = listOf(
        Stick("demo1", "SanDisk Ultra Fit", true, 32_000_000_000),
        Stick("demo2", "Kingston DataTraveler", false, null),
    )

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
                lines,
            )
            "done" -> Jobs.demo(JobState.Finished(Outcome.OK, "", name, "SanDisk Ultra Fit"), lines)
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
        val first = if (demo == "confirm") CACHY else catalog.firstOrNull { it.enabled }?.id
        var distroId by rememberSaveable { mutableStateOf(if (demo == "file") ForgeService.CUSTOM_ID else first) }
        var stickKey by rememberSaveable { mutableStateOf(if (demo != null && demo != "empty") "demo1" else null) }
        var confirming by rememberSaveable { mutableStateOf(demo == "confirm" || demo == "confirm-ready") }
        var hint by remember { mutableStateOf("") }
        // A file the person picked to write instead of a listed system.
        var fileUri by rememberSaveable { mutableStateOf<String?>(if (demo == "file") "content://demo/new-os.img.bz2" else null) }
        var fileName by rememberSaveable { mutableStateOf(if (demo == "file") "new-os.img.bz2" else "") }
        var fileSize by rememberSaveable { mutableStateOf(if (demo == "file") 3_400_000_000L else -1L) }
        var fileKind by rememberSaveable { mutableStateOf(if (demo == "file") ImageKind.XZ.name else ImageKind.UNKNOWN.name) }
        val kind = ImageKind.valueOf(fileKind)
        val scope = rememberCoroutineScope()
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

        // Watch for sticks being plugged in or pulled out while choosing.
        LaunchedEffect(job is JobState.Idle, tick) {
            while (job is JobState.Idle && demo == null) {
                val now = withContext(Dispatchers.IO) { sticks.list(probe = true) }
                list = now
                if (now.none { it.key == stickKey }) stickKey = now.singleOrNull()?.key
                delay(2000)
            }
        }
        LaunchedEffect(job) { keepScreenOn(job is JobState.Running) }

        val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val custom = distroId == ForgeService.CUSTOM_ID && fileUri != null
        // A picked file runs as a one-off "system" named after the file.
        val distro = if (custom) {
            Distro(ForgeService.CUSTOM_ID, fileName, kind.label, true, 1, null, emptyList(), warning = kind.warning)
        } else {
            catalog.firstOrNull { it.id == distroId }
        }
        val stick = list.firstOrNull { it.key == stickKey }

        BackHandler(enabled = confirming && job is JobState.Idle) { confirming = false }

        Box(Modifier.fillMaxSize().background(Forge.Slate).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth()) {
                    Txt("Handheld Forge", Forge.brand)
                    Spacer(Modifier.weight(1f))
                    Txt("v${BuildConfigCompat.versionName(this@MainActivity)}", Forge.brand)
                }
                Gap(18)
                when (val j = job) {
                    is JobState.Running -> Working(j, askStopFirst = demo == "stop")
                    is JobState.Finished -> Result(j, catalog.firstOrNull { it.name == j.distro }) {
                        Jobs.reset()
                        confirming = false
                    }
                    JobState.Idle -> if (confirming && distro != null && stick != null) {
                        Confirm(distro, stick, custom, onBack = { confirming = false }) {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            if (custom) {
                                ForgeService.startFile(this@MainActivity, Uri.parse(fileUri), fileName, stick.key, stick.label)
                            } else {
                                ForgeService.start(this@MainActivity, distro, stick.key, stick.label)
                            }
                        }
                    } else {
                        Choose(
                            catalog, distroId, fileName.takeIf { fileUri != null }, fileSize, kind, list, stickKey, hint,
                            onDistro = { distroId = it },
                            onPickFile = { pickFile.launch(ForgeService.PICK_TYPES) },
                            onStick = { s ->
                                stickKey = s.key
                                hint = ""
                                if (!s.hasPermission) sticks.device(s.key)?.let { sticks.requestPermission(it) }
                            },
                            onCreate = {
                                val size = stick?.sizeBytes
                                when {
                                    distro == null || stick == null -> {}
                                    !stick.hasPermission -> sticks.device(stick.key)?.let { sticks.requestPermission(it) }
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
        onStick: (Stick) -> Unit,
        onCreate: () -> Unit,
    ) {
        Txt("Pick a system and a USB drive.", Forge.title)
        val fileChosen = distroId == ForgeService.CUSTOM_ID && pickedName != null
        Gap(18)
        Row(horizontalArrangement = RowGap) {
            catalog.forEach { d ->
                Tile(
                    d.name, d.edition, selected = d.id == distroId, enabled = d.enabled,
                    tag = if (d.enabled) "" else "Soon", subtitleLines = 2, modifier = Modifier.weight(1f),
                ) { onDistro(d.id) }
            }
        }
        Gap(10)
        // Anything else: an ISO or disk image already on the phone, for systems not listed here yet.
        Tile(
            pickedName ?: "Your own file",
            if (pickedName == null) "Pick an ISO or disk image on this phone"
            else listOfNotNull(pickedKind.label, if (pickedSize > 0) gb(pickedSize) else null).joinToString(", "),
            selected = fileChosen, tag = if (fileChosen) "Change" else "", modifier = Modifier.fillMaxWidth(),
        ) { if (fileChosen || pickedName == null) onPickFile() else onDistro(ForgeService.CUSTOM_ID) }
        Gap(14)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = RowGap) {
            if (list.isEmpty()) {
                EmptySlot("Plug in a USB drive of 16 GB or more with a USB-C adapter")
            }
            list.forEach { s ->
                val sub = when {
                    !s.hasPermission -> "Tap to allow access"
                    s.sizeBytes != null -> gb(s.sizeBytes)
                    else -> "Reading size"
                }
                Tile(s.label, sub, selected = s.key == stickKey, usb = true, modifier = Modifier.fillMaxWidth()) { onStick(s) }
            }
        }
        if (hint.isNotEmpty()) {
            Txt(hint, Forge.hint.copy(color = Forge.Danger))
            Gap(10)
        }
        val ready = ((fileChosen && pickedKind.writable) || catalog.any { it.id == distroId && it.enabled }) &&
            list.any { it.key == stickKey }
        MoltenButton(if (fileChosen) "Write to drive" else "Create boot drive", enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = onCreate)
    }

    @Composable
    private fun ColumnScope.Confirm(distro: Distro, stick: Stick, custom: Boolean, onBack: () -> Unit, onErase: () -> Unit) {
        val resumed by resumeTick.collectAsState()
        var phone by remember { mutableStateOf(phoneState()) }
        // Battery and network change while the person reads; keep it current.
        LaunchedEffect(resumed) {
            while (true) {
                phone = phoneState()
                delay(2000)
            }
        }
        val checks = Preflight.checks(phone, download = !custom)
        val problems = checks.filter { !it.ok }

        Txt("Erase this drive?", Forge.title)
        Gap(8)
        Txt("Everything on it will be replaced with ${distro.name}.", Forge.hint)
        Gap(14)
        Tile(stick.label, stick.sizeBytes?.let { gb(it) } ?: "", selected = true, usb = true, modifier = Modifier.fillMaxWidth())
        Gap(16)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
                "Erase and write", enabled = Preflight.canStart(checks), modifier = Modifier.weight(1f), onClick = onErase,
            )
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

        Txt(if (j.local) "Writing ${j.distro} to ${j.drive}" else "Creating ${j.distro} on ${j.drive}", Forge.title)
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

    @Composable
    private fun ColumnScope.Result(j: JobState.Finished, distro: Distro?, onAgain: () -> Unit) {
        var showLog by remember { mutableStateOf(false) }
        val log by Jobs.log.collectAsState()
        val steps = if (j.outcome == Outcome.OK) distro?.installSteps.orEmpty() else emptyList()
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
                    Outcome.OK -> "Ready to boot"
                    Outcome.CANCELLED -> "Stopped"
                    Outcome.ERROR -> "The drive wasn't finished"
                },
                Forge.title,
            )
            Gap(8)
            Txt(
                if (j.outcome == Outcome.OK) {
                    "${j.drive} now holds ${j.distro}. " + if (steps.isEmpty()) {
                        "Plug it into your handheld, open the boot menu and choose the USB drive."
                    } else {
                        "Plug it into your handheld and follow these steps."
                    }
                } else {
                    j.message
                },
                Forge.hint,
            )
            if (steps.isNotEmpty()) InstallGuide(steps)
        }
        Gap(12)
        if (showLog) LogBox(log)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietButton("Details") { showLog = !showLog }
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

    /** Opening the boot menu, then the system's own install steps. */
    @Composable
    private fun InstallGuide(steps: List<String>) {
        val body = TextStyle(color = Forge.Bone, fontSize = 14.sp, lineHeight = 20.sp)
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
