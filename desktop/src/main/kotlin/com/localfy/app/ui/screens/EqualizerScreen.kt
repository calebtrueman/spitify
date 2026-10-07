package com.localfy.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.playback.EqFrequencies
import com.localfy.app.playback.EqPresets
import com.localfy.app.playback.EqStore
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.components.Pill
import com.localfy.app.ui.components.SectionHeader
import com.localfy.app.ui.components.rememberHaptics
import com.localfy.app.ui.theme.LocalfyColors
import kotlin.math.abs
import kotlin.math.roundToInt

private fun hzLabel(hz: Float) = if (hz >= 1000) "${(hz / 1000).let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }}k" else hz.toInt().toString()

@Composable
fun EqualizerScreen() {
    val app = LocalApp.current
    val state by EqStore.state.collectAsStateWithLifecycle()
    val caps by EqStore.capabilities.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val accent = MaterialTheme.colorScheme.primary
    val maxDb = 12f

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
        item {
            com.localfy.app.ui.components.PageHeader("Equaliser", onBack = { app.nav.popBackStack() }) {
                Switch(state.enabled, { v -> haptics(HapticFeedbackType.ToggleOn); EqStore.update { it.copy(enabled = v) } }, Modifier.padding(end = 12.dp))
            }
            Text(caps?.engine ?: "Start playback to connect the sound engine", style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp))
        }

        // Interactive response curve: drag any point up or down; double-tap resets that band.
        item {
            Column(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LocalfyColors.Tint)
                    .alpha(if (state.enabled) 1f else 0.45f).padding(vertical = 16.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    Text(state.preset, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("±${maxDb.toInt()} dB", style = MaterialTheme.typography.labelMedium, color = LocalfyColors.TextSecondary)
                }
                Spacer(Modifier.height(8.dp))
                var dragging by remember { mutableIntStateOf(-1) }
                val gridColor = LocalfyColors.TextPrimary.copy(alpha = 0.08f)
                val lineColor = LocalfyColors.TextPrimary.copy(alpha = 0.25f)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(horizontal = 16.dp)
                        .pointerInput(Unit) {
                            fun bandAt(x: Float) = ((x / size.width) * (EqFrequencies.size - 1)).roundToInt().coerceIn(0, EqFrequencies.lastIndex)
                            fun dbAt(y: Float) = (((size.height / 2f) - y) / (size.height / 2f) * maxDb).coerceIn(-maxDb, maxDb)
                            detectDragGestures(
                                onDragStart = { o -> dragging = bandAt(o.x); haptics(HapticFeedbackType.GestureThresholdActivate) },
                                onDragEnd = { dragging = -1 },
                                onDragCancel = { dragging = -1 },
                            ) { change, _ ->
                                change.consume()
                                val db = (dbAt(change.position.y) * 2).roundToInt() / 2f // 0.5 dB steps
                                if (dragging >= 0 && db != EqStore.state.value.gains[dragging]) EqStore.setGain(dragging, db)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { o ->
                                val band = ((o.x / size.width) * (EqFrequencies.size - 1)).roundToInt().coerceIn(0, EqFrequencies.lastIndex)
                                EqStore.setGain(band, 0f)
                            })
                        },
                ) {
                    val w = size.width
                    val h = size.height
                    fun x(i: Int) = w * i / (EqFrequencies.size - 1)
                    fun y(db: Float) = h / 2f - (db / maxDb) * (h / 2f)
                    listOf(-maxDb, -6f, 0f, 6f, maxDb).forEach { db ->
                        drawLine(if (db == 0f) lineColor else gridColor, Offset(0f, y(db)), Offset(w, y(db)), strokeWidth = 2f,
                            pathEffect = if (db == 0f) null else PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                    }
                    // Smooth curve through the band points (Catmull-Rom -> cubic Bézier).
                    val pts = state.gains.mapIndexed { i, g -> Offset(x(i), y(g)) }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 0 until pts.lastIndex) {
                            val p0 = pts[maxOf(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(i + 2, pts.lastIndex)]
                            cubicTo(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f, p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f, p2.x, p2.y)
                        }
                    }
                    val fill = Path().apply { addPath(path); lineTo(w, h / 2f); lineTo(0f, h / 2f); close() }
                    drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f))))
                    drawPath(path, accent, style = Stroke(width = 6f, cap = StrokeCap.Round))
                    pts.forEachIndexed { i, p ->
                        drawCircle(accent, radius = if (i == dragging) 22f else 13f, center = p)
                        drawCircle(androidx.compose.ui.graphics.Color.White, radius = if (i == dragging) 9f else 5f, center = p)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    EqFrequencies.forEachIndexed { i, hz ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(hzLabel(hz), style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextSecondary, textAlign = TextAlign.Center)
                            val g = state.gains[i]
                            Text(
                                (if (g > 0) "+" else "") + (if (abs(g % 1f) < 0.01f) g.toInt().toString() else "%.1f".format(g)),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (abs(g) > 0.01f) accent else LocalfyColors.TextTertiary,
                            )
                        }
                    }
                }
                Text(
                    "Drag the points to shape the sound · double-tap a point to reset it",
                    style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
            }
        }

        item { SectionHeader("Presets") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EqPresets) { p -> Pill(p.name, state.preset == p.name, { haptics(HapticFeedbackType.ContextClick); EqStore.applyPreset(p) }) }
            }
        }

        item { SectionHeader("Sound") }
        item {
            Column(Modifier.padding(horizontal = 16.dp).alpha(if (state.enabled) 1f else 0.45f)) {
                EffectSlider("Bass boost", "${(state.bass * 100).roundToInt()}%", state.bass, 0f..1f, caps?.bassBoost != false) { v -> EqStore.update { it.copy(bass = v, enabled = true) } }
                EffectSlider("Surround", "${(state.surround * 100).roundToInt()}%", state.surround, 0f..1f, caps?.virtualizer != false) { v -> EqStore.update { it.copy(surround = v, enabled = true) } }
                EffectSlider("Loudness", "+%.1f dB".format(state.loudnessDb), state.loudnessDb, 0f..10f, caps?.loudness != false) { v -> EqStore.update { it.copy(loudnessDb = (v * 2).roundToInt() / 2f, enabled = true) } }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Clipping protection", style = MaterialTheme.typography.bodyLarge)
                        Text("A gentle limiter so boosted bands never distort", style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
                    }
                    Switch(state.limiter, { v -> EqStore.update { it.copy(limiter = v) } })
                }
            }
        }
    }
}

@Composable
private fun EffectSlider(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, supported: Boolean, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp).alpha(if (supported) 1f else 0.4f)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (supported) value else "Not supported", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            current, onChange, valueRange = range, enabled = supported,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

