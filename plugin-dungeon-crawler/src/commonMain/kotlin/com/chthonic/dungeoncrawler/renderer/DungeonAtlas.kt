package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

// Texture atlas for the dungeon view. All tiles must be the same size, arranged in a
// uniform grid with [cols] columns. Pass a DungeonAtlas to DungeonRendererManager to
// enable RenderMode.TEXTURED; without one, TEXTURED falls back to SOLID.
data class DungeonAtlas(
    val image: ImageBitmap,
    val tileSize: Int,
    val cols: Int,
    val frontWallTile: Int = 1,
    val sideWallTile: Int = 1,
    val floorTile: Int = 2,
    val ceilTile: Int = 0,
) {
    fun srcOffset(tileIndex: Int) = IntOffset(
        x = (tileIndex % cols) * tileSize,
        y = (tileIndex / cols) * tileSize,
    )

    fun srcSize() = IntSize(tileSize, tileSize)
}
