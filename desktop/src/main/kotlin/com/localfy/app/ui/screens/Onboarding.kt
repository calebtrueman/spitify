package com.localfy.app.ui.screens

import com.localfy.app.ui.typingFocus

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.ui.art.AsyncImage
import com.localfy.app.LocalfyApp
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.components.pressable
import com.localfy.app.ui.theme.LocalPalette
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull


/** First-run setup: welcome → name → photo → music access → favourite artists. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val app = com.localfy.app.ui.LocalContainer.current
    val profile by app.profiles.profile.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(profile.name) }
    val accent = MaterialTheme.colorScheme.primary

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(accent, Color.Black, 0.55f), LocalfyColors.Background, LocalfyColors.Background)))
            ,
    ) {
        AnimatedContent(
            step,
            transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) },
            modifier = Modifier.fillMaxSize(),
            label = "onboarding",
        ) { s ->
            Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Dots(s, 5)
                Spacer(Modifier.height(24.dp))
                when (s) {
                    0 -> Step(
                        title = "Welcome to Spitify",
                        body = "Your music, podcasts and audiobooks — on your computer, no account, no ads. Playlists made for you, learned right here on the device.",
                        primary = "Get started", onPrimary = { step = 1 },
                    ) {
                        Box(Modifier.size(120.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                            Text("S", fontSize = 64.sp, fontWeight = FontWeight.Black, color = LocalPalette.current.onBrand)
                        }
                    }
                    1 -> Step(
                        title = "What should we call you?",
                        body = "It's used for things like “Made for you” — it never leaves your computer.",
                        primary = "Next", primaryEnabled = name.isNotBlank(),
                        onPrimary = { app.profiles.setName(name); step = 2 },
                    ) {
                        OutlinedTextField(
                            name, { name = it.take(30) }, singleLine = true, placeholder = { Text("Your name") },
                            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) { app.profiles.setName(name); step = 2 } }),
                            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().typingFocus(),
                        )
                    }
                    2 -> PhotoStep(name, onNext = { step = 3 })
                    3 -> FolderStep(onNext = { step = 4 })
                    else -> ArtistStep(onDone = { picked ->
                        app.profiles.setSeedArtists(picked)
                        app.profiles.completeOnboarding()
                        onDone()
                    })
                }
            }
        }
    }
}

@Composable
private fun Dots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(Modifier.size(width = if (i == current) 22.dp else 8.dp, height = 8.dp).clip(CircleShape)
                .background(if (i <= current) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f)))
        }
    }
}

@Composable
private fun Step(
    title: String, body: String, primary: String, onPrimary: () -> Unit,
    primaryEnabled: Boolean = true, secondary: String? = null, onSecondary: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        content()
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = LocalfyColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
        Spacer(Modifier.height(36.dp))
        Text(
            primary, style = MaterialTheme.typography.titleMedium, color = LocalPalette.current.onBrand,
            modifier = Modifier.clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (primaryEnabled) 1f else 0.4f))
                .pressable { if (primaryEnabled) onPrimary() }.padding(horizontal = 40.dp, vertical = 16.dp),
        )
        if (secondary != null && onSecondary != null) TextButton(onClick = onSecondary, modifier = Modifier.padding(top = 8.dp)) { Text(secondary, color = LocalfyColors.TextSecondary) }
    }
}

@Composable
private fun PhotoStep(name: String, onNext: () -> Unit) {
    val app = com.localfy.app.ui.LocalContainer.current
    val window = com.localfy.app.ui.LocalWindow.current
    val profile by app.profiles.profile.collectAsStateWithLifecycle()
    fun pick() { com.localfy.app.ui.FilePickers.pickImage(window, "Choose a profile photo")?.let { app.profiles.setPhoto(it) } }
    Step(
        title = "Add a profile picture",
        body = "Pick a photo, or keep your initial.",
        primary = if (profile.hasPhoto) "Looks good" else "Choose photo",
        onPrimary = { if (profile.hasPhoto) onNext() else pick() },
        secondary = if (profile.hasPhoto) "Choose a different photo" else "Skip for now",
        onSecondary = { if (profile.hasPhoto) pick() else onNext() },
    ) {
        Box(
            Modifier.size(150.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).pressable { pick() },
            contentAlignment = Alignment.Center,
        ) {
            if (profile.hasPhoto) {
                AsyncImage(app.profiles.photoFile, "Profile photo", Modifier.fillMaxSize(), ContentScale.Crop, cacheKey = "profile:${profile.photoVersion}")
            } else {
                Text(name.trim().firstOrNull()?.uppercase() ?: "?", fontSize = 72.sp, fontWeight = FontWeight.Black, color = LocalPalette.current.onBrand)
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Desktop has no media permission: instead, confirm which folders hold your music. */
@Composable
private fun FolderStep(onNext: () -> Unit) {
    val app = com.localfy.app.ui.LocalContainer.current
    val window = com.localfy.app.ui.LocalWindow.current
    val folders by app.library.folders.collectAsStateWithLifecycle()
    Step(
        title = "Find your music",
        body = "Spitify plays the songs, podcasts and audiobooks already on this computer. Choose the folders that hold them — you can change this any time in Settings.",
        primary = if (folders.isEmpty()) "Choose a folder" else "Continue",
        onPrimary = {
            if (folders.isEmpty()) com.localfy.app.ui.FilePickers.pickFolder(window, "Choose your music folder")?.let { app.library.addFolder(it) }
            else { app.library.refresh(); onNext() }
        },
        secondary = if (folders.isEmpty()) null else "Add another folder",
        onSecondary = { com.localfy.app.ui.FilePickers.pickFolder(window, "Add a music folder")?.let { app.library.addFolder(it) } },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp))
            folders.take(5).forEach { folder ->
                Text(folder.path, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 460.dp))
            }
        }
    }
}

@Composable
private fun ArtistStep(onDone: (Set<String>) -> Unit) {
    val app = com.localfy.app.ui.LocalContainer.current
    val library by app.library.library.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(true) }
    var picked by rememberSaveable { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) {
        app.library.ensureStarted()
        withTimeoutOrNull(8_000) { app.library.library.first { !it.isEmpty } }
        loading = false
    }
    val artists = library.artists.filterNot { it.name.startsWith("Unknown", true) }.sortedByDescending { it.songs.size }.take(30)
    if (!loading && artists.isEmpty()) { LaunchedEffect(Unit) { onDone(emptySet()) }; return }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Pick a few artists you love", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text("Your first mixes start here — they'll keep learning from everything you play.", style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        if (loading) {
            Spacer(Modifier.weight(1f)); CircularProgressIndicator(); Spacer(Modifier.weight(1f))
        } else {
            LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                items(artists, key = { it.name }) { a ->
                    val on = a.name in picked
                    Column(Modifier.padding(8.dp).pressable { picked = if (on) picked - a.name else picked + a.name }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Artwork(
                                a.cover.artKey,
                                Modifier.fillMaxWidth().aspectRatio(1f).border(if (on) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                CircleShape,
                            )
                            if (on) Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = LocalPalette.current.onBrand)
                            }
                        }
                        Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        Text(
            if (picked.isEmpty()) "Skip" else "Done (${picked.size})",
            style = MaterialTheme.typography.titleMedium,
            color = if (picked.isEmpty()) LocalfyColors.TextPrimary else LocalPalette.current.onBrand,
            modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(50))
                .background(if (picked.isEmpty()) LocalfyColors.Tint else MaterialTheme.colorScheme.primary)
                .pressable { onDone(picked) }.padding(horizontal = 40.dp, vertical = 14.dp),
        )
    }
}
