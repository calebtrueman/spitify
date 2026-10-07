package com.localfy.app.ui.art

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.localfy.app.data.Song
import com.localfy.app.ui.theme.ArtShape
import com.localfy.app.ui.theme.LocalThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Artwork is per album; any song of the album can be used to extract the embedded picture. */
data class ArtKey(val songId: Long, val albumId: Long, val url: String? = null, val version: Long = 0) {
    /** What the loader fetches: a remote/file URL, or this key (album art from the library). */
    val model: Any get() = url ?: this
}

val com.localfy.app.data.Playlist.artKey: ArtKey? get() = artwork?.let { ArtKey(id, id, it, artVersion) } ?: songs.firstOrNull()?.artKey

val Song.artKey: ArtKey get() = ArtKey(id, albumId, artUrl, artVersion)

/** Deterministic, pleasant fallback colour per album so missing art still looks designed. */
fun fallbackColor(seed: Long): Color {
    val palette = listOf(
        Color(0xFF8E44AD), Color(0xFF1E88E5), Color(0xFFE5533D), Color(0xFF00897B),
        Color(0xFFF4A300), Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFF43A047),
    )
    return palette[(abs(seed) % palette.size).toInt()]
}

/**
 * Loads [model] at the size it's drawn. A bitmap already in memory shows immediately (no flash
 * when a row scrolls back in); a freshly decoded one fades in.
 */
@Composable
private fun rememberImage(model: Any?, cacheKey: String? = null): LoadedImage {
    val modelKey = remember(model, cacheKey) { cacheKey ?: model?.let(ImageLoader::modelKey) }
    var px by remember(modelKey) { mutableIntStateOf(0) }
    val initial = remember(modelKey) { modelKey?.let(ImageLoader::peek) }
    val image by produceState(initial, modelKey, px) {
        if (model == null || px <= 0) return@produceState
        val cached = value
        if (cached != null && maxOf(cached.width, cached.height) >= ImageLoader.bucket(px)) return@produceState
        ImageLoader.load(model, px, modelKey ?: ImageLoader.modelKey(model))?.let { value = it }
    }
    return LoadedImage(image, instant = initial != null) { size -> if (size > px) px = size }
}

private class LoadedImage(val image: ImageBitmap?, val instant: Boolean, val measured: (Int) -> Unit) {
    operator fun component1() = image
    operator fun component2() = measured
}

/** A plain async image (podcast/remote covers, friend photos, bundled theme art). */
@Composable
fun AsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cacheKey: String? = null,
) {
    val loaded = rememberImage(model, cacheKey)
    val image = loaded.image
    Box(modifier.onSizeChanged { loaded.measured(maxOf(it.width, it.height)) }) {
        if (image != null) FadeInImage(image, contentDescription, contentScale, loaded.instant)
    }
}

@Composable
private fun FadeInImage(image: ImageBitmap, contentDescription: String?, contentScale: ContentScale, instant: Boolean) {
    // Images already in memory skip the fade, so lists don't shimmer while scrolling.
    val alpha = remember { Animatable(if (instant) 1f else 0f) }
    LaunchedEffect(Unit) { if (alpha.value < 1f) alpha.animateTo(1f, tween(180)) }
    Image(
        image, contentDescription,
        Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
        contentScale = contentScale, filterQuality = FilterQuality.Medium,
    )
}

@Composable
fun Artwork(
    key: ArtKey?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    contentDescription: String? = null,
) {
    val base = fallbackColor(key?.albumId ?: 0)
    // Appearance setting: rounded (as designed), square, or extra-round artwork. Circles stay circles.
    val artShape = LocalThemeSettings.current.artShape
    val clip = when {
        shape === CircleShape || shape === RectangleShape -> shape
        artShape == ArtShape.Square -> RectangleShape
        artShape == ArtShape.Soft -> RoundedCornerShape(18.dp)
        else -> shape
    }
    val loaded = rememberImage(key?.model)
    val image = loaded.image
    Box(
        modifier
            .clip(clip)
            .onSizeChanged { loaded.measured(maxOf(it.width, it.height)) }
            .background(Brush.linearGradient(listOf(base, lerp(base, Color.Black, 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(0.4f),
            )
        } else {
            FadeInImage(image, contentDescription, ContentScale.Crop, loaded.instant)
        }
    }
}

/** Extracts a dark, readable accent from the artwork - the Spotify "gradient behind the album". */
@Composable
fun rememberArtColor(key: ArtKey?, fallback: Color = Color(0xFF2A2A2E)): State<Color> {
    val tint = LocalThemeSettings.current.artworkTint
    return produceState(initialValue = if (tint) key?.let { colorCache["${it.albumId}:${it.version}:${it.url}"] ?: fallbackColor(it.albumId).darken() } ?: fallback else fallback, key, tint) {
        if (key == null || !tint) { value = fallback; return@produceState }
        value = extractColor(key) ?: fallbackColor(key.albumId).darken()
    }
}

/** A bright, saturated colour from the artwork - used for the "accent from album art" theme. */
@Composable
fun rememberArtAccent(key: ArtKey?): Color? {
    return produceState<Color?>(null, key) {
        if (key == null) { value = null; return@produceState }
        val bitmap = ImageLoader.load(key.model, 96) ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val p = ArtPalette.from(bitmap)
            (p.vibrant ?: p.lightVibrant ?: p.dominant)?.let { c ->
                // Keep it lively enough to work as a button colour on dark and light backgrounds.
                if (c.luminance() < 0.18f) lerp(c, Color.White, 0.35f) else c
            }
        }
    }.value
}

private val colorCache = java.util.concurrent.ConcurrentHashMap<String, Color>()

private suspend fun extractColor(key: ArtKey): Color? {
    val cacheKey = "${key.albumId}:${key.version}:${key.url}"
    colorCache[cacheKey]?.let { return it }
    val bitmap = ImageLoader.load(key.model, 96) ?: return null
    val color = withContext(Dispatchers.Default) {
        val p = ArtPalette.from(bitmap)
        (p.vibrant ?: p.darkVibrant ?: p.dominant ?: p.muted)?.darken()
    }
    if (color != null) colorCache[cacheKey] = color
    return color
}

private fun Color.darken(): Color = lerp(this, Color.Black, 0.35f)
