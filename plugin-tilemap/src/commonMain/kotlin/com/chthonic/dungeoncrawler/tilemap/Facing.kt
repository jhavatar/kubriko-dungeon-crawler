package com.chthonic.dungeoncrawler.tilemap

/**
 * One of the four cardinal directions a [GridPosition] can face on a [TileMap].
 */
enum class Facing(val dx: Int, val dy: Int) {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0);

    fun opposite(): Facing = entries[(ordinal + 2) % entries.size]

    fun turnedLeft(): Facing = entries[(ordinal + 3) % entries.size]

    fun turnedRight(): Facing = entries[(ordinal + 1) % entries.size]
}
