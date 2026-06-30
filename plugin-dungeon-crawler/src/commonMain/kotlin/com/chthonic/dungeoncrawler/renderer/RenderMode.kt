package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.Color

sealed class RenderMode {

    data class Wireframe(
        val frontColor: Color = Color(0xFF6B4F3B),
        val sideColor: Color = Color(0xFF4A3728),
    ) : RenderMode()

    data class Solid(
        val colorTheme: DungeonColorTheme = DungeonColorTheme(),
    ) : RenderMode()

    // atlas is required — Textured cannot be constructed without one.
    data class Textured(
        val atlas: DungeonAtlas,
        val colorTheme: DungeonColorTheme = DungeonColorTheme(),
    ) : RenderMode()
}
