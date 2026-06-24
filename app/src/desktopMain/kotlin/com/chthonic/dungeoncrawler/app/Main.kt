package com.chthonic.dungeoncrawler.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kubriko Dungeon Crawler POC",
        state = rememberWindowState(size = DpSize(800.dp, 800.dp)),
    ) {
        DungeonCrawlerApp()
    }
}
