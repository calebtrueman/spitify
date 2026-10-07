package com.localfy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfy.app.ui.theme.LocalfyColors

/** Consistent safe-area spacing and title size for pages outside a collection. */
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().statusBarsPadding()) {
        com.localfy.app.ui.theme.ThemeScene(compact = true)
        if (onBack != null) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (onBack == null) 12.dp else 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (onBack == null) actions()
        }
    }
}

/** The same cover, text and tap spacing as a song row, for other kinds of media. */
@Composable
fun MediaRow(title: String, subtitle: String, artwork: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}, subtitleContent: (@Composable () -> Unit)? = null, isCurrent: Boolean = false, isPlaying: Boolean = false) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            artwork(Modifier.size(50.dp))
            if (isCurrent) Box(Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                EqualizerBars(isPlaying, size = 18.dp, color = Color.White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = if (isCurrent) MaterialTheme.colorScheme.primary else LocalfyColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitleContent != null) subtitleContent()
            else if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Keeps form content aligned while allowing the page header to reach both edges. */
fun androidx.compose.foundation.lazy.LazyListScope.insetItem(content: @Composable () -> Unit) {
    item { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() } }
}
