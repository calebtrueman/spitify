package com.localfy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.ui.art.AsyncImage
import com.localfy.app.data.CoverStyle
import com.localfy.app.data.Mix
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.theme.LocalPalette

/**
 * Covers for generated playlists, so they read as "made for you" at a glance:
 * Collage = 2×2 of the albums inside with a colour band and title (Daily Mix style);
 * Bold = a deep gradient with large type (Discover Weekly / daylist style).
 */
@Composable
fun MixCover(mix: Mix, modifier: Modifier = Modifier) {
    val accent = Color(mix.accent)
    BoxWithConstraints(modifier) {
        val side = maxWidth
        val small = side < 120.dp
        if (side < 80.dp) {
            // Thumbnails (quick picks, queue): just the collage/artwork, no type.
            val arts = mix.songs.distinctBy { it.albumId }.take(4)
            if (arts.size >= 4) Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) { arts.take(2).forEach { Artwork(it.artKey, Modifier.weight(1f).fillMaxSize()) } }
                Row(Modifier.weight(1f)) { arts.drop(2).forEach { Artwork(it.artKey, Modifier.weight(1f).fillMaxSize()) } }
            } else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent, lerp(accent, Color.Black, 0.5f))))) {
                Artwork(mix.cover.artKey, Modifier.align(Alignment.Center).fillMaxSize(0.62f))
            }
            return@BoxWithConstraints
        }
        val titleSize = with(LocalDensity.current) { (side.toPx() / if (small) 6.5f else 8.5f).toSp() }
        when (mix.style) {
            CoverStyle.Collage -> {
                val arts = mix.songs.distinctBy { it.albumId }.take(4)
                if (arts.size >= 4) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f)) { arts.take(2).forEach { Artwork(it.artKey, Modifier.weight(1f).fillMaxSize()) } }
                        Row(Modifier.weight(1f)) { arts.drop(2).forEach { Artwork(it.artKey, Modifier.weight(1f).fillMaxSize()) } }
                    }
                } else {
                    Artwork(mix.cover.artKey, Modifier.fillMaxSize())
                }
            }
            CoverStyle.Bold -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(accent, Color.White, 0.15f), accent, lerp(accent, Color.Black, 0.55f)))))
                Artwork(
                    mix.cover.artKey,
                    Modifier.align(Alignment.BottomEnd).padding(if (small) 6.dp else 12.dp).size(side * 0.38f).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                )
                Text(
                    mix.title.removePrefix("daylist • ").let { if (mix.key == "daylist") "daylist" else it },
                    color = Color.White, fontSize = titleSize, lineHeight = titleSize * 1.05f, fontWeight = FontWeight.Black,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(if (small) 8.dp else 14.dp),
                )
            }
        }
    }
}

/** The listener's avatar: their photo, or their initial on the accent colour. */
@Composable
fun Avatar(size: Dp, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val profile by app.profiles.profile.collectAsStateWithLifecycle()
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        if (profile.hasPhoto) {
            AsyncImage(
                model = app.profiles.photoFile, cacheKey = "profile:${profile.photoVersion}",
                contentDescription = "Profile photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else if (profile.name.isNotBlank()) {
            Text(profile.name.trim().first().uppercase(), color = LocalPalette.current.onBrand, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Black)
        } else {
            Icon(Icons.Rounded.Person, null, tint = LocalPalette.current.onBrand, modifier = Modifier.size(size * 0.6f))
        }
    }
}
