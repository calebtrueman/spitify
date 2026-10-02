package com.localfy.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localfy.app.ui.theme.LocalThemeSettings
import com.localfy.app.ui.theme.LocalfyColors

/** Click with a springy shrink-on-press, the tactile feel used on every card and tile. */
fun Modifier.pressable(
    onLongClick: (() -> Unit)? = null,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberHaptics()
    val still = LocalThemeSettings.current.reduceMotion
    val scale by animateFloatAsState(
        if (pressed && !still) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick?.let { { haptics(HapticFeedbackType.LongPress); it() } },
        )
}

/** Plain click without ripple (for areas that already have their own visual response). */
fun Modifier.quietClickable(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/** Three bouncing bars shown next to the song that's currently playing. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = LocalfyColors.Brand, size: Dp = 16.dp) {
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0.9f, 0.3f, infiniteRepeatable(tween(560, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "c")
    Canvas(modifier.size(size)) {
        val bar = this.size.width / 5
        listOf(a, b, c).forEachIndexed { i, v ->
            val h = this.size.height * (if (playing) v else 0.3f)
            drawRoundRect(
                color,
                topLeft = Offset(bar * (i * 2f), this.size.height - h),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2, bar / 2),
            )
        }
    }
}

/** Animated loading placeholder. */
fun Modifier.shimmer(): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    background(
        Brush.linearGradient(
            listOf(LocalfyColors.SurfaceHigh, LocalfyColors.SurfaceHighest, LocalfyColors.SurfaceHigh),
            start = Offset(x * 600f, 0f),
            end = Offset(x * 600f + 400f, 400f),
        ),
    )
}

@Composable
fun rememberHaptics(): (HapticFeedbackType) -> Unit {
    val h = LocalHapticFeedback.current
    val enabled = LocalThemeSettings.current.haptics
    return remember(h, enabled) { { type -> if (enabled) h.performHapticFeedback(type) } }
}

/** Darkens the strip behind the status bar so scrolled content never fights the clock/icons. */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier, color: Color = LocalfyColors.Background) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0.3f)))),
    )
}

/** Soft fade at the top and bottom edges of a scrolling list (used for lyrics). */
fun Modifier.fadingEdges(top: Dp = 32.dp, bottom: Dp = 48.dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val t = top.toPx() / size.height
        val b = 1f - bottom.toPx() / size.height
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, t to Color.Black, b to Color.Black, 1f to Color.Transparent),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }
