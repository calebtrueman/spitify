package com.localfy.app.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Read the selected media output, not the list of merely connected devices. */
internal fun audioOutputName(context: Context): String {
    if (Build.VERSION.SDK_INT >= 33) {
        val manager = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val outputs = runCatching { manager.getAudioDevicesForAttributes(attributes) }.getOrDefault(emptyList())
        if (outputs.isNotEmpty()) return outputs.map { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
                else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio device"
            }
        }.distinct().joinToString(" + ")
    }
    val router = context.getSystemService(MediaRouter::class.java)
    return runCatching { router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO).name.toString() }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "Audio output unavailable"
}

@Composable
internal fun rememberAudioOutput(): State<String> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(audioOutputName(context), context, lifecycle) {
        // Check while visible, including switches between already-connected devices.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = audioOutputName(context)
                delay(1000)
            }
        }
    }
}
