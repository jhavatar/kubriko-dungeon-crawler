package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.Color

sealed class RenderMode {

    data class Wireframe(
        val frontColor: Color = Color(0xFFFFBB66),  // bright amber — walls
        val sideColor: Color = frontColor,
        val floorColor: Color = Color(0xFF66BB66),  // bright green — floor
        val ceilColor: Color = Color(0xFF6688FF),   // bright blue — ceiling
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
