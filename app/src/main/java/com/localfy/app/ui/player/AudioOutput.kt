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

/** Let Android route media, including Bluetooth and the phone speaker. */
internal fun showAudioOutputs(context: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        val router = android.media.MediaRouter2.getInstance(context)
        val token = com.localfy.app.playback.AudioSessionHolder.platformToken
        val shown = runCatching {
            // Bind the picker to this player. Otherwise Android can choose another app's session.
            if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 && token != null)
                router.showSystemOutputSwitcher(token)
            else router.showSystemOutputSwitcher()
        }.getOrDefault(false)
        if (shown) return
    }
    // Older Android versions have no public in-app output switcher. Offer actual system routes
    // before falling back to Bluetooth settings; a volume panel alone cannot switch the player.
    val router = android.media.MediaRouter2.getInstance(context)
    val routes = router.routes.filter { it.isSystemRoute }
    if (routes.size > 1) {
        android.app.AlertDialog.Builder(context).setTitle("Play audio on")
            .setItems(routes.map { it.name.toString() }.toTypedArray()) { _, which ->
                router.transferTo(routes[which])
            }.setNegativeButton("Cancel", null).show()
        return
    }
    val panel = android.content.Intent(android.provider.Settings.Panel.ACTION_VOLUME)
    runCatching { context.startActivity(panel) }.onFailure {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
    }
}
