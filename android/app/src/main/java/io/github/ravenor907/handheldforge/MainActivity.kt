package io.github.ravenor907.handheldforge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ravenor907.handheldforge.core.Distro
import io.github.ravenor907.handheldforge.core.Stage
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
import kotlinx.coroutines.withContext

val STEPS = listOf("Download", "Verify", "Write", "Check")
private val STEP_WEIGHT = listOf(45, 8, 37, 10)

private fun stepOf(s: Stage) = when (s) {
    Stage.LOOKUP, Stage.DOWNLOAD -> 0
    Stage.VERIFY -> 1
    Stage.WRITE -> 2
    Stage.CHECK -> 3
}

private fun gb(bytes: Long) = if (bytes >= 10e9) "%.0f GB".format(bytes / 1e9) else "%.1f GB".format(bytes / 1e9)

class MainActivity : ComponentActivity() {
    private lateinit var sticks: UsbSticks
    private var receiver: BroadcastReceiver? = null
    private val permissionTick = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always light system-bar icons: the app is dark regardless of the phone's theme.
        val bars = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        sticks = UsbSticks(this)
        receiver = sticks.registerPermissionReceiver { permissionTick.value++ }
        val catalog = ForgeService.catalog(this)
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val demo = if (debuggable) intent.getStringExtra("demo") else null
        if (demo != null) startDemo(demo, catalog)
        setContent { App(catalog, demo) }
    }

    override fun onDestroy() {
        receiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val demoSticks = listOf(
        Stick("demo1", "SanDisk Ultra Fit", true, 32_000_000_000),
        Stick("demo2", "Kingston DataTraveler", false, null),
    )

    private fun startDemo(demo: String, catalog: List<Distro>) {
        val name = catalog.first { it.enabled }.name
        val lines = listOf("09:12:01  Using Live ISO (current installer)", "09:12:02  Latest sha256 4c1f0e9a...")
        when (demo) {
            "working" -> Jobs.demo(JobState.Running(name, "SanDisk Ultra Fit", Stage.WRITE, 4_100_000_000, 8_300_000_000), lines)
            "done" -> Jobs.demo(JobState.Finished(Outcome.OK, "", name, "SanDisk Ultra Fit"), lines)
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
        var distroId by rememberSaveable { mutableStateOf(catalog.firstOrNull { it.enabled }?.id) }
        var stickKey by rememberSaveable { mutableStateOf(if (demo != null && demo != "empty") "demo1" else null) }
        var confirming by rememberSaveable { mutableStateOf(demo == "confirm") }
        var hint by remember { mutableStateOf("") }
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
        val distro = catalog.firstOrNull { it.id == distroId }
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
                    is JobState.Running -> Working(j)
                    is JobState.Finished -> Result(j) {
                        Jobs.reset()
                        confirming = false
                    }
                    JobState.Idle -> if (confirming && distro != null && stick != null) {
                        Confirm(distro, stick, onBack = { confirming = false }) {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            ForgeService.start(this@MainActivity, distro, stick.key, stick.label)
                        }
                    } else {
                        Choose(
                            catalog, distroId, list, stickKey, hint,
                            onDistro = { distroId = it },
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
                                    size != null && size < distro.minDriveBytes ->
                                        hint = "That drive is too small. ${distro.name} needs ${distro.minDriveGb} GB or more."
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
        list: List<Stick>,
        stickKey: String?,
        hint: String,
        onDistro: (String) -> Unit,
        onStick: (Stick) -> Unit,
        onCreate: () -> Unit,
    ) {
        Txt("Pick a system and a USB drive.", Forge.title)
        Gap(18)
        Row(horizontalArrangement = RowGap) {
            catalog.forEach { d ->
                Tile(
                    d.name, d.edition, selected = d.id == distroId, enabled = d.enabled,
                    tag = if (d.enabled) "" else "Soon", subtitleLines = 2, modifier = Modifier.weight(1f),
                ) { onDistro(d.id) }
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
        val ready = catalog.any { it.id == distroId && it.enabled } && list.any { it.key == stickKey }
        MoltenButton("Create boot drive", enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = onCreate)
    }

    @Composable
    private fun ColumnScope.Confirm(distro: Distro, stick: Stick, onBack: () -> Unit, onErase: () -> Unit) {
        Spacer(Modifier.weight(1f))
        Txt("Erase this drive?", Forge.title)
        Gap(8)
        Txt("Everything on it will be replaced with ${distro.name}.", Forge.hint)
        Gap(20)
        Tile(stick.label, stick.sizeBytes?.let { gb(it) } ?: "", selected = true, usb = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(2f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietButton("Go back", onClick = onBack)
            MoltenButton("Erase and create", modifier = Modifier.weight(1f), onClick = onErase)
        }
    }

    @Composable
    private fun ColumnScope.Working(j: JobState.Running) {
        val step = stepOf(j.stage)
        val frac = if (j.total > 0) j.done.toFloat() / j.total else 0f
        val overall = (STEP_WEIGHT.take(step).sum() + STEP_WEIGHT[step] * frac) / STEP_WEIGHT.sum()
        var showLog by remember { mutableStateOf(false) }
        val log by Jobs.log.collectAsState()

        Txt("Creating ${j.distro} on ${j.drive}", Forge.title)
        Spacer(Modifier.weight(1f))
        Txt("${(overall * 100).toInt()}%", Forge.big)
        HeatBar(overall, active = true)
        Gap(10)
        val text = when (j.stage) {
            Stage.LOOKUP -> "Checking for the latest release"
            Stage.DOWNLOAD -> "Downloading ${j.distro}"
            Stage.VERIFY -> "Making sure the download is genuine"
            Stage.WRITE -> "Writing to the drive"
            Stage.CHECK -> "Reading the drive back to confirm"
        } + if (j.total > 0 && j.stage in setOf(Stage.DOWNLOAD, Stage.WRITE)) {
            ", %.1f of %.1f GB".format(j.done / 1e9, j.total / 1e9)
        } else {
            ""
        }
        Txt(text, Forge.hint)
        Spacer(Modifier.weight(1f))
        StepTrack(STEPS, step, failed = false)
        Gap(18)
        Row {
            QuietButton(if (showLog) "Hide details" else "Details") { showLog = !showLog }
            Spacer(Modifier.weight(1f))
            QuietButton(if (j.stopping) "Stopping" else "Stop", enabled = !j.stopping) { Jobs.cancel() }
        }
        if (showLog) LogBox(log)
    }

    @Composable
    private fun ColumnScope.Result(j: JobState.Finished, onAgain: () -> Unit) {
        var showLog by remember { mutableStateOf(false) }
        val log by Jobs.log.collectAsState()
        Spacer(Modifier.weight(1f))
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
                "${j.drive} now holds ${j.distro}. Plug it into your handheld, open the boot menu and choose the USB drive."
            } else {
                j.message
            },
            Forge.hint,
        )
        Spacer(Modifier.weight(2f))
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
