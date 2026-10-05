package com.localfy.app.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.localfy.app.LocalfyApp
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.components.rememberHaptics
import com.localfy.app.ui.theme.LocalThemeSettings
import com.localfy.app.ui.theme.PlayerStyle

private val PlayerStyle.next: PlayerStyle
    get() = when (this) { PlayerStyle.Artwork -> PlayerStyle.Vinyl; PlayerStyle.Vinyl -> PlayerStyle.Minimal; PlayerStyle.Minimal -> PlayerStyle.Artwork }

/** A separate button leaves the record's drag-to-seek gesture untouched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtworkStyleButton() {
    val theme = (LocalContext.current.applicationContext as LocalfyApp).theme
    val style = LocalThemeSettings.current.playerStyle
    val haptics = rememberHaptics()
    val hint = "Switch to ${style.next.label.lowercase()}"
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = rememberTooltipState(),
        tooltip = { PlainTooltip { Text("Artwork style: ${style.label}. $hint") } }) {
        IconButton(onClick = {
            haptics(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
            theme.update { it.copy(playerStyle = style.next) }
        }, modifier = Modifier.semantics { stateDescription = "${style.label}. $hint" }) {
            Icon(when (style) { PlayerStyle.Artwork -> Icons.Rounded.Image; PlayerStyle.Vinyl -> Icons.Rounded.Album; PlayerStyle.Minimal -> Icons.Rounded.CropSquare }, "Artwork style")
        }
    }
}
