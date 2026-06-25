package com.chthonic.dungeoncrawler.tilemap

/**
 * Any entity that occupies a single cell on the [TileMap] and can move: the player party or a monster.
 */
class Mob(
    initialCellX: Int = 0,
    initialCellY: Int = 0,
    initialFacing: Facing = Facing.NORTH,
) : GridPosition {
    override var cellX: Int = initialCellX
    override var cellY: Int = initialCellY
    override var facing: Facing = initialFacing
}
