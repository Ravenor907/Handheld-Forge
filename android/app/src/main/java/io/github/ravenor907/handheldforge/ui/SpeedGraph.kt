package io.github.ravenor907.handheldforge.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.ravendisk.core.job.SpeedPhase
import io.github.ravenor907.ravendisk.core.job.SpeedSample
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The live speed graph, the same one Raven Disk Utility shows (same samples from the shared
 * engine, same reading), in the forge's colors: writes molten, reads a cool steel blue.
 */
fun phaseColor(p: SpeedPhase): Color = when (p) {
    SpeedPhase.WRITE -> Forge.MoltenA
    SpeedPhase.SCATTER_WRITE -> Color(0xFFF7D58A)
    SpeedPhase.READ -> Color(0xFF7FB2FF)
    SpeedPhase.SCATTER_READ -> Color(0xFFB9D3FF)
}

private fun niceCeil(v: Double): Double {
    if (v <= 0) return 10.0
    val p = 10.0.pow(floor(log10(v)))
    for (m in listOf(1.0, 2.0, 2.5, 5.0, 10.0)) if (m * p >= v) return m * p
    return ceil(v / p) * p
}

/** Every point is the speed over the last quarter second, so cache drops and stalls show as they happen. */
@Composable
fun SpeedGraph(samples: List<SpeedSample>, live: Boolean, modifier: Modifier = Modifier) {
    val last = samples.lastOrNull()
    val small = TextStyle(color = Forge.Ash, fontSize = 13.sp, lineHeight = 17.sp)
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Txt(
                last?.let { "%.0f".format(it.mbps) } ?: "-",
                TextStyle(color = last?.let { phaseColor(it.phase) } ?: Forge.Ash, fontSize = 40.sp, fontWeight = FontWeight.Light),
            )
            Txt(" MB/s", TextStyle(color = Forge.Ash, fontSize = 17.sp, fontWeight = FontWeight.SemiBold), Modifier.padding(bottom = 7.dp))
            Spacer(Modifier.weight(1f))
            if (last != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Txt(if (live) "${last.phase.label} now" else "Finished", small.copy(color = phaseColor(last.phase)))
                    val same = samples.filter { it.phase == last.phase }.map { it.mbps }
                    Txt("avg %.0f · peak %.0f · low %.0f".format(same.average(), same.max(), same.min()), small)
                }
            }
        }
        Gap(8)
        val top = niceCeil((samples.maxOfOrNull { it.mbps } ?: 0.0) * 1.1)
        val yMax by animateFloatAsState(top.toFloat(), tween(400), label = "y")
        val xMax by animateFloatAsState(maxOf(10.0, (last?.seconds ?: 0.0) * 1.05).toFloat(), tween(250), label = "x")
        val measurer = rememberTextMeasurer()
        val axis = TextStyle(color = Forge.Ash.copy(alpha = 0.7f), fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp)).background(Forge.Iron)) {
            val padL = 40.dp.toPx()
            val padB = 18.dp.toPx()
            val padT = 10.dp.toPx()
            val w = size.width - padL - 8.dp.toPx()
            val h = size.height - padB - padT
            fun x(s: Double) = padL + (s / xMax).toFloat() * w
            fun y(v: Double) = padT + h - (v / yMax).toFloat().coerceIn(0f, 1.05f) * h
            for (f in listOf(0.0, 0.5, 1.0)) {
                val v = yMax * f
                val yy = y(v)
                drawLine(
                    Forge.Seam, Offset(padL, yy), Offset(padL + w, yy), 1f,
                    pathEffect = if (f == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                val t = measurer.measure("%.0f".format(v), axis)
                drawText(t, topLeft = Offset(padL - t.size.width - 6.dp.toPx(), yy - t.size.height / 2))
            }
            val tx = measurer.measure("%.0f s".format(xMax), axis)
            drawText(tx, topLeft = Offset(padL + w - tx.size.width, size.height - tx.size.height))
            if (samples.isEmpty()) return@Canvas
            var i = 0
            while (i < samples.size) {
                val p = samples[i].phase
                var j = i
                while (j + 1 < samples.size && samples[j + 1].phase == p) j++
                val run = samples.subList(i, j + 1)
                val c = phaseColor(p)
                val line = Path()
                run.forEachIndexed { k, s -> if (k == 0) line.moveTo(x(s.seconds), y(s.mbps)) else line.lineTo(x(s.seconds), y(s.mbps)) }
                if (run.size == 1) line.lineTo(x(run[0].seconds) + 1f, y(run[0].mbps))
                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(run.last().seconds), y(0.0))
                    lineTo(x(run.first().seconds), y(0.0))
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(c.copy(alpha = 0.28f), c.copy(alpha = 0.02f)), startY = padT, endY = padT + h))
                drawPath(line, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                i = j + 1
            }
            if (live) {
                val s = samples.last()
                drawCircle(phaseColor(s.phase), 4.dp.toPx(), Offset(x(s.seconds), y(s.mbps)))
                drawCircle(phaseColor(s.phase).copy(alpha = 0.25f), 9.dp.toPx(), Offset(x(s.seconds), y(s.mbps)))
            }
        }
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            samples.map { it.phase }.distinct().forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(phaseColor(p)))
                    Spacer(Modifier.width(5.dp))
                    Txt(p.label, small)
                }
            }
        }
    }
}
