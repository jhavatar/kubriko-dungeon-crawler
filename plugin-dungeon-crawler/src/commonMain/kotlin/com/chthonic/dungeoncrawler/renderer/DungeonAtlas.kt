package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.ImageBitmap

// Texture atlas for the dungeon view. All tiles must be the same size, arranged in a
// uniform grid with [cols] columns. Canonical tile ordering: wall=0, floor=1, ceiling=2.
// Pass a DungeonAtlas to RenderMode.Textured to enable textured rendering.
data class DungeonAtlas(
    val image: ImageBitmap,
    val tileSize: Int,
    val cols: Int,
    val frontWallTile: Int = 0,
    val sideWallTile: Int = 0,
    val floorTile: Int = 1,
    val ceilTile: Int = 2,
) {
    init {
        val maxIndex = (image.width / tileSize) * (image.height / tileSize) - 1
        require(listOf(frontWallTile, sideWallTile, floorTile, ceilTile).all { it in 0..maxIndex }) {
            "Tile index out of range (atlas has ${maxIndex + 1} tiles): " +
                "frontWall=$frontWallTile, sideWall=$sideWallTile, floor=$floorTile, ceil=$ceilTile"
        }
    }
}
