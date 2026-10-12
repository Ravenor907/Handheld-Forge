package io.github.ravenor907.handheldforge.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Forge {
    val Slate = Color(0xFF1B2026)
    val Iron = Color(0xFF252B33)
    val IronHi = Color(0xFF2C333C)
    val Seam = Color(0xFF353D47)
    val Ash = Color(0xFF98A2AE)
    val Bone = Color(0xFFECEFF2)
    val MoltenA = Color(0xFFF5B83D)
    val MoltenB = Color(0xFFE4572E)
    val Danger = Color(0xFFF07167)
    val Ink = Color(0xFF1A1410)
    val molten = Brush.horizontalGradient(listOf(MoltenA, MoltenB))

    val title = TextStyle(color = Bone, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
    val hint = TextStyle(color = Ash, fontSize = 15.sp, lineHeight = 21.sp)
    val brand = TextStyle(color = Ash, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val big = TextStyle(color = Bone, fontSize = 56.sp, fontWeight = FontWeight.Light)
}

@Composable
fun Txt(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Selectable card. Selected = molten edge, disabled = dimmed with an optional tag. */
@Composable
fun Tile(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String = "",
    usb: Boolean = false,
    subtitleLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    var m = modifier.height(84.dp).clip(shape).background(Forge.Iron)
    if (selected) m = m.border(2.5.dp, Forge.molten, shape)
    if (onClick != null && enabled) m = m.clickable(role = Role.RadioButton, onClick = onClick)
    Box(m.alpha(if (enabled) 1f else 0.45f)) {
        val pad = if (subtitleLines > 1) 12.dp else 16.dp
        Row(Modifier.padding(horizontal = pad).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            if (usb) {
                UsbGlyph(selected)
                Spacer(Modifier.width(16.dp))
            }
            Column {
                Txt(title, TextStyle(color = Forge.Bone, fontSize = 17.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Txt(subtitle, TextStyle(color = Forge.Ash, fontSize = if (subtitleLines > 1) 13.sp else 14.sp, lineHeight = 16.sp), maxLines = subtitleLines)
            }
        }
        if (tag.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(10.dp))
                    .background(Forge.Seam).padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Txt(tag, TextStyle(color = Forge.Bone, fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
fun UsbGlyph(hot: Boolean) {
    Canvas(Modifier.size(width = 32.dp, height = 22.dp)) {
        val u = size.height / 22f
        val body = if (hot) Forge.molten else SolidColor(Forge.Ash)
        drawRoundRect(body, Offset(0f, 1 * u), Size(22 * u, 20 * u), CornerRadius(4 * u))
        drawRoundRect(Forge.Seam.copy(alpha = 1f), Offset(22 * u, 5 * u), Size(9 * u, 12 * u), CornerRadius(2 * u))
        drawRect(Forge.Slate, Offset(25 * u, 8 * u), Size(3 * u, 2 * u))
        drawRect(Forge.Slate, Offset(25 * u, 12 * u), Size(3 * u, 2 * u))
    }
}

/** Dashed placeholder where a drive will appear. */
@Composable
fun EmptySlot(text: String) {
    Box(
        Modifier.fillMaxWidth().height(84.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(
                Forge.Seam, cornerRadius = CornerRadius(14.dp.toPx()),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        }
        Txt(text, Forge.hint.copy(textAlign = TextAlign.Center), Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
fun MoltenButton(text: String, enabled: Boolean = true, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(16.dp)
    val bg = when {
        !enabled -> Modifier.background(Forge.Iron, shape)
        danger -> Modifier.background(Forge.Danger, shape)
        else -> Modifier.background(Forge.molten, shape)
    }
    Box(
        modifier.height(64.dp).clip(shape).then(bg).alpha(if (pressed) 0.85f else 1f)
            .clickable(enabled = enabled, interactionSource = src, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            text,
            TextStyle(color = if (enabled) Forge.Ink else Forge.Ash.copy(alpha = 0.6f), fontSize = 19.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
fun QuietButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(52.dp).clip(shape).border(1.dp, Forge.Seam, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, TextStyle(color = if (enabled) Forge.Bone else Forge.Ash, fontSize = 15.sp))
    }
}

/** Progress bar whose filled part glows, with a slow heat shimmer while active. */
@Composable
fun HeatBar(progress: Float, active: Boolean, modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "heat").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Forge.Iron, cornerRadius = r)
        val w = (size.width * progress.coerceIn(0f, 1f)).coerceAtLeast(if (active) size.height else 0f)
        if (w <= 0f) return@Canvas
        drawRoundRect(Brush.horizontalGradient(listOf(Forge.MoltenA, Forge.MoltenB), 0f, size.width), size = Size(w, size.height), cornerRadius = r)
        if (active) {
            val x = (phase * 1.6f - 0.3f) * size.width
            val shine = Brush.horizontalGradient(
                listOf(Color(0x00FFFFFF), Color(0x5AFFF0C8), Color(0x00FFFFFF)), x - 120f, x + 120f,
            )
            drawRoundRect(shine, size = Size(w, size.height), cornerRadius = r)
        }
    }
}

/** Download, Verify, Write, Check: done steps solid, current molten. */
@Composable
fun StepTrack(steps: List<String>, current: Int, failed: Boolean) {
    Box(Modifier.fillMaxWidth().height(46.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val n = steps.size
            val y = 10.dp.toPx()
            val xs = (0 until n).map { size.width * (it + 0.5f) / n }
            drawLine(Forge.Seam, Offset(xs.first(), y), Offset(xs.last(), y), 2.dp.toPx())
            xs.forEachIndexed { i, x ->
                val brush = when {
                    i < current -> SolidColor(Forge.Ash)
                    i == current && failed -> SolidColor(Forge.Danger)
                    i == current -> Forge.molten
                    else -> SolidColor(Forge.Seam)
                }
                drawCircle(brush, 6.dp.toPx(), Offset(x, y))
            }
        }
        Row(Modifier.matchParentSize(), verticalAlignment = Alignment.Bottom) {
            steps.forEachIndexed { i, s ->
                Txt(
                    s,
                    TextStyle(
                        color = if (i == current) Forge.Bone else Forge.Ash, fontSize = 13.sp,
                        fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center,
                    ),
                    Modifier.weight(1f),
                )
            }
        }
    }
}

/** Ring with a check (done), a bang (error) or a bar (stopped). */
@Composable
fun StatusMark(kind: String) {
    Canvas(Modifier.size(64.dp)) {
        val u = size.width / 64f
        val brush = when (kind) {
            "ok" -> Forge.molten
            "error" -> SolidColor(Forge.Danger)
            else -> SolidColor(Forge.Ash)
        }
        drawCircle(brush, 28 * u, center, style = Stroke(4 * u))
        val st = Stroke(5 * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "ok" -> drawPath(Path().apply { moveTo(20 * u, 33 * u); lineTo(28 * u, 41 * u); lineTo(44 * u, 24 * u) }, brush, style = st)
            "error" -> {
                drawLine(brush, Offset(32 * u, 19 * u), Offset(32 * u, 35 * u), 5 * u, StrokeCap.Round)
                drawCircle(brush, 3 * u, Offset(32 * u, 44 * u))
            }
            else -> drawLine(brush, Offset(22 * u, 32 * u), Offset(42 * u, 32 * u), 5 * u, StrokeCap.Round)
        }
    }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

val RowGap = Arrangement.spacedBy(10.dp)

/** One line of the "Before you start" list: ok, warning (optional) or blocking (required). */
@Composable
fun CheckRow(title: String, detail: String, ok: Boolean, required: Boolean, onClick: (() -> Unit)? = null) {
    var m = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(m.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = when {
            ok -> Forge.MoltenA
            required -> Forge.Danger
            else -> Forge.Ash
        }
        Canvas(Modifier.size(24.dp)) {
            val u = size.width / 24f
            drawCircle(color, 11 * u, center, style = Stroke(2 * u))
            val st = Stroke(2.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when {
                ok -> drawPath(Path().apply { moveTo(7 * u, 12.5f * u); lineTo(10.5f * u, 16 * u); lineTo(17 * u, 9 * u) }, color, style = st)
                required -> {
                    drawLine(color, Offset(8.5f * u, 8.5f * u), Offset(15.5f * u, 15.5f * u), 2.5f * u, StrokeCap.Round)
                    drawLine(color, Offset(15.5f * u, 8.5f * u), Offset(8.5f * u, 15.5f * u), 2.5f * u, StrokeCap.Round)
                }
                else -> {
                    drawLine(color, Offset(12 * u, 7 * u), Offset(12 * u, 13 * u), 2.5f * u, StrokeCap.Round)
                    drawCircle(color, 1.5f * u, Offset(12 * u, 17 * u))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, TextStyle(color = Forge.Bone, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Txt(detail, TextStyle(color = if (ok || !required) Forge.Ash else Forge.Danger, fontSize = 13.sp, lineHeight = 17.sp))
        }
    }
}

/** A tick box the person has to tick themselves, e.g. agreeing to leave the phone alone. */
@Composable
fun AckBox(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Forge.Iron)
            .then(if (checked) Modifier.border(2.dp, Forge.molten, shape) else Modifier.border(1.dp, Forge.Seam, shape))
            .clickable(role = Role.Checkbox) { onChange(!checked) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val u = size.width / 24f
            val r = CornerRadius(5 * u)
            if (checked) {
                drawRoundRect(Forge.molten, cornerRadius = r)
                drawPath(
                    Path().apply { moveTo(6 * u, 12.5f * u); lineTo(10 * u, 16.5f * u); lineTo(18 * u, 8 * u) }, Forge.Ink,
                    style = Stroke(2.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            } else {
                drawRoundRect(Forge.Ash, cornerRadius = r, style = Stroke(2 * u))
            }
        }
        Spacer(Modifier.width(14.dp))
        Txt(text, TextStyle(color = Forge.Bone, fontSize = 14.sp, lineHeight = 19.sp), Modifier.weight(1f))
    }
}

/** A plain warning strip, e.g. "keep this screen open" while a job runs. */
@Composable
fun Banner(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.fillMaxWidth().clip(shape).background(Forge.Iron).border(1.dp, Forge.MoltenA.copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Txt(text, TextStyle(color = Forge.Bone, fontSize = 14.sp, lineHeight = 19.sp))
    }
}
