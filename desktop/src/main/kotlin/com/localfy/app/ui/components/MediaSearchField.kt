package com.localfy.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfy.app.ui.DesktopEvents
import com.localfy.app.ui.theme.LocalPalette
import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.typingFocus

/**
 * One search field treatment for music, podcasts and books.
 * [shortcutTarget]: the field Cmd/Ctrl+F jumps to (the music search).
 */
@Composable
fun MediaSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, onSearch: (() -> Unit)? = null, shortcutTarget: Boolean = false) {
    val background = if (LocalPalette.current.isDark) Color.White else LocalfyColors.SurfaceHigh
    val focus = remember { FocusRequester() }
    if (shortcutTarget) LaunchedEffect(Unit) {
        DesktopEvents.focusSearch.collect { tick -> if (tick > 0) { runCatching { focus.requestFocus() }; DesktopEvents.focusSearch.value = 0 } }
    }
    TextField(
        value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).focusRequester(focus).typingFocus(),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color.Black) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(Icons.Rounded.Close, "Clear", tint = Color.Black) } },
        singleLine = true, shape = RoundedCornerShape(8.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = if (onSearch != null) KeyboardActions(onSearch = { onSearch() }, onDone = { onSearch() }) else KeyboardActions.Default,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = background, unfocusedContainerColor = background,
            focusedTextColor = Color.Black, unfocusedTextColor = Color.Black,
            focusedPlaceholderColor = Color(0xFF555555), unfocusedPlaceholderColor = Color(0xFF555555),
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Color.Black,
        ),
    )
}
