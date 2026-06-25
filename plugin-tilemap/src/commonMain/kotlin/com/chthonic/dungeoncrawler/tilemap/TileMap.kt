package com.chthonic.dungeoncrawler.tilemap

/**
 * A 2D grid of dungeon cells following "worm tunnel" conventions: every cell has a [CellType] and
 * a wall is a full cell, not a shared edge. The outer ring is always [CellType.WALL].
 */
class TileMap(
    val width: Int,
    val height: Int,
) {
    private val cells = Array(width * height) { index ->
        val x = index % width
        val y = index / width
        if (x == 0 || x == width - 1 || y == 0 || y == height - 1) CellType.WALL else CellType.OPEN
    }

    fun cellTypeAt(x: Int, y: Int): CellType = if (isInBounds(x, y)) cells[indexOf(x, y)] else CellType.WALL

    fun setCellType(x: Int, y: Int, type: CellType) {
        cells[indexOf(x, y)] = type
    }

    fun isInBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    private fun indexOf(x: Int, y: Int) = y * width + x
}
