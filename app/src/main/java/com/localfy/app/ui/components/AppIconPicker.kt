package com.localfy.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.localfy.app.icons.AppIconChoice
import com.localfy.app.icons.AppIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppIconSettingsRow() {
    val context = LocalContext.current
    val icons = remember(context) { AppIcons(context) }
    var selected by remember { mutableStateOf(icons.selectedId()) }
    var showing by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycle, icons) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) selected = icons.selectedId() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val choice = icons.choices.firstOrNull { it.id == selected }
    Row(Modifier.fillMaxWidth().clickable { selected = icons.selectedId(); showing = true }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (choice != null) IconPreview(icons, choice, Modifier.size(52.dp))
        Column(Modifier.weight(1f)) {
            Text("App icon", style = MaterialTheme.typography.titleMedium)
            Text(choice?.name ?: "Choose your look", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
    }
    if (showing) {
        ModalBottomSheet(onDismissRequest = { if (!changing) showing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxHeight(0.94f)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose your app icon", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showing = false }, enabled = !changing) { Icon(Icons.Rounded.Close, "Close icon picker") }
                }
                Text("A new look for your Home screen.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (changing) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Changing app icon" })
                message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
                if (icons.choices.isEmpty()) Text("Icons couldn't load. Close this screen and try again.", Modifier.padding(20.dp))
                LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 28.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Attitude", "Artwork", "Kids").forEach { group ->
                        val choices = icons.choices.filter { it.group == group }
                        if (choices.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "group-$group") {
                                Text(group, Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                            }
                            items(choices, key = { it.id }) { icon ->
                                val isSelected = selected == icon.id
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                                    .border(if (isSelected) 2.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(16.dp))
                                    .clickable(enabled = !changing, role = Role.RadioButton) {
                                        if (isSelected) return@clickable
                                        changing = true; message = null
                                        scope.launch {
                                            try { withContext(Dispatchers.IO) { icons.select(icon.id) } }
                                            catch (error: Exception) { if (error is CancellationException) throw error; message = error.message ?: "Couldn't change the icon. Please try again." }
                                            finally { selected = icons.selectedId(); changing = false }
                                        }
                                    }.semantics(mergeDescendants = true) { this.selected = isSelected; contentDescription = icon.name }
                                    .padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box {
                                        IconPreview(icons, icon, Modifier.size(78.dp))
                                        if (isSelected) Icon(Icons.Rounded.CheckCircle, "Selected", Modifier.align(Alignment.BottomEnd).size(22.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)), tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(icon.name, style = MaterialTheme.typography.labelLarge, minLines = 2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconPreview(icons: AppIcons, choice: AppIconChoice, modifier: Modifier) {
    val resource = remember(icons, choice.id) { icons.previewResource(choice) }
    if (resource != 0) Image(painterResource(resource), null, modifier.clip(RoundedCornerShape(18.dp)))
    else Box(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
}
