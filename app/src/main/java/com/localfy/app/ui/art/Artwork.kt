package com.localfy.app.ui.art

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.localfy.app.ui.theme.ArtShape
import com.localfy.app.ui.theme.LocalThemeSettings
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.pxOrElse
import coil3.toBitmap
import com.localfy.app.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.abs

/** Artwork is per album; any song of the album can be used to extract the embedded picture. */
data class ArtKey(val songId: Long, val albumId: Long, val url: String? = null, val version: Long = 0) {
    /** What Coil loads: a remote URL (podcasts) or our MediaStore fetcher key. */
    val model: Any get() = url ?: this
}

val Song.artKey: ArtKey get() = ArtKey(id, albumId, artUrl, artVersion)

class ArtKeyer : Keyer<ArtKey> {
    override fun key(data: ArtKey, options: Options): String = "album:${data.albumId}:${data.version}"
}

class ArtFetcher(
    private val context: Context,
    private val key: ArtKey,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val px = options.size.width.pxOrElse { 512 }.coerceIn(64, 1600)
        val bitmap = withContext(Dispatchers.IO) {
            loadCustom(px) ?: loadThumbnail(px) ?: loadAlbumArt(px) ?: loadOnline(px)
        } ?: throw FileNotFoundException("No artwork for album ${key.albumId}")
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    /** Artwork the user picked (or chose from online results) wins over everything. */
    private fun loadCustom(px: Int): Bitmap? {
        val app = context.applicationContext as? com.localfy.app.LocalfyApp ?: return null
        return app.metadata.customArt(key.albumId)?.let { decodeSampled(it, px) }
    }

    /** No embedded art: use (or fetch) a cover from Deezer / iTunes. */
    private suspend fun loadOnline(px: Int): Bitmap? {
        val app = context.applicationContext as? com.localfy.app.LocalfyApp ?: return null
        val file = app.onlineArt.cached(key.albumId) ?: run {
            val album = app.library.library.value.albumById[key.albumId] ?: return null
            app.onlineArt.fetch(key.albumId, album.artist, album.title)
        } ?: return null
        return decodeSampled(file, px)
    }

    private fun loadThumbnail(px: Int): Bitmap? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, key.songId)
        context.contentResolver.loadThumbnail(uri, Size(px, px), null)
    }.getOrNull()

    private fun loadAlbumArt(px: Int): Bitmap? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, key.albumId)
        context.contentResolver.loadThumbnail(uri, Size(px, px), null)
    }.getOrNull()

    class Factory(private val context: Context) : Fetcher.Factory<ArtKey> {
        override fun create(data: ArtKey, options: Options, imageLoader: ImageLoader): Fetcher =
            ArtFetcher(context, data, options)
    }
}

internal fun decodeSampled(file: java.io.File, px: Int): Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= px) sample *= 2
    android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** Deterministic, pleasant fallback colour per album so missing art still looks designed. */
fun fallbackColor(seed: Long): Color {
    val palette = listOf(
        Color(0xFF8E44AD), Color(0xFF1E88E5), Color(0xFFE5533D), Color(0xFF00897B),
        Color(0xFFF4A300), Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFF43A047),
    )
    return palette[(abs(seed) % palette.size).toInt()]
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
    Box(
        modifier
            .clip(clip)
            .background(Brush.linearGradient(listOf(base, lerp(base, Color.Black, 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(0.4f),
        )
        if (key != null) {
            AsyncImage(
                model = key.model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Extracts a dark, readable accent from the artwork - the Spotify "gradient behind the album". */
@Composable
fun rememberArtColor(key: ArtKey?, fallback: Color = Color(0xFF2A2A2E)): State<Color> {
    val context = LocalContext.current
    val tint = LocalThemeSettings.current.artworkTint
    return produceState(initialValue = if (tint) key?.let { fallbackColor(it.albumId).darken() } ?: fallback else fallback, key, tint) {
        if (key == null || !tint) { value = fallback; return@produceState }
        value = extractColor(context, key) ?: fallbackColor(key.albumId).darken()
    }
}

/** A bright, saturated colour from the artwork - used for the "accent from album art" theme. */
@Composable
fun rememberArtAccent(key: ArtKey?): Color? {
    val context = LocalContext.current
    return produceState<Color?>(null, key) {
        if (key == null) { value = null; return@produceState }
        val request = ImageRequest.Builder(context).data(key.model).size(96).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val p = Palette.from(bitmap).maximumColorCount(16).generate()
            (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch)?.let { sw ->
                val c = Color(sw.rgb)
                // Keep it lively enough to work as a button colour on dark and light backgrounds.
                if (c.luminance() < 0.18f) lerp(c, Color.White, 0.35f) else c
            }
        }
    }.value
}

private val colorCache = HashMap<String, Color>()

private suspend fun extractColor(context: Context, key: ArtKey): Color? {
    colorCache["${key.albumId}:${key.version}"]?.let { return it }
    val request = ImageRequest.Builder(context).data(key.model).size(96).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val bitmap = result.image.toBitmap()
    val color = withContext(Dispatchers.Default) {
        val p = Palette.from(bitmap).maximumColorCount(16).generate()
        val swatch = p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch
        swatch?.let { Color(it.rgb).darken() }
    }
    if (color != null) colorCache["${key.albumId}:${key.version}"] = color
    return color
}

private fun Color.darken(): Color = lerp(this, Color.Black, 0.35f)
