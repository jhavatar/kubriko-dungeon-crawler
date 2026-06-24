package com.chthonic.dungeoncrawler.tilemap

/**
 * A 2D grid of dungeon cells. Walls are stored per cell edge so that adjacent cells share a single
 * source of truth for the wall between them. The outer boundary of the map is walled by default.
 */
class TileMap(
    val width: Int,
    val height: Int,
) {
    private val northWalls = BooleanArray(width * height)
    private val westWalls = BooleanArray(width * height)

    init {
        for (x in 0 until width) northWalls[indexOf(x, 0)] = true
        for (y in 0 until height) westWalls[indexOf(0, y)] = true
    }

    fun hasWallNorth(x: Int, y: Int): Boolean = northWalls[indexOf(x, y)]

    fun hasWallSouth(x: Int, y: Int): Boolean = if (y + 1 < height) northWalls[indexOf(x, y + 1)] else true

    fun hasWallWest(x: Int, y: Int): Boolean = westWalls[indexOf(x, y)]

    fun hasWallEast(x: Int, y: Int): Boolean = if (x + 1 < width) westWalls[indexOf(x + 1, y)] else true

    fun setWallNorth(x: Int, y: Int, hasWall: Boolean) {
        northWalls[indexOf(x, y)] = hasWall
    }

    fun setWallWest(x: Int, y: Int, hasWall: Boolean) {
        westWalls[indexOf(x, y)] = hasWall
    }

    fun isInBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    private fun indexOf(x: Int, y: Int) = y * width + x
}
