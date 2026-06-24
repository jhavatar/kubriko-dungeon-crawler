package com.chthonic.dungeoncrawler.tilemap

/**
 * Default [GridPosition] implementation for anything that occupies a single cell, such as the
 * player or a monster.
 */
class GridActor(
    initialCellX: Int = 0,
    initialCellY: Int = 0,
    initialFacing: Facing = Facing.NORTH,
) : GridPosition {
    override var cellX: Int = initialCellX
    override var cellY: Int = initialCellY
    override var facing: Facing = initialFacing
}
