package com.localfy.app

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** Placeholder until the UI is ported. */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Spitify") { Text("Spitify Desktop") }
}
