package com.chthonic.dungeoncrawler.tilemap

import com.pandulapeter.kubriko.manager.Manager

/**
 * Owns the [TileMap] for the active dungeon level and resolves move/turn legality against it.
 * Party movement: [moveForward], [moveBackward], [strafeLeft], [strafeRight].
 * Monster movement: [moveForward], [moveBackward] only (per map conventions).
 */
class TileMapManager(
    initialTileMap: TileMap,
    isLoggingEnabled: Boolean = false,
    instanceNameForLogging: String? = null,
) : Manager(
    isLoggingEnabled = isLoggingEnabled,
    instanceNameForLogging = instanceNameForLogging,
    classNameForLogging = "TileMapManager",
) {

    var tileMap: TileMap = initialTileMap
        private set

    fun canMoveTo(position: GridPosition, facing: Facing = position.facing): Boolean =
        tileMap.cellTypeAt(position.cellX + facing.dx, position.cellY + facing.dy) != CellType.WALL

    fun moveForward(position: GridPosition): Boolean = tryStep(position, position.facing)

    fun moveBackward(position: GridPosition): Boolean = tryStep(position, position.facing.opposite())

    fun strafeLeft(position: GridPosition): Boolean = tryStep(position, position.facing.turnedLeft())

    fun strafeRight(position: GridPosition): Boolean = tryStep(position, position.facing.turnedRight())

    private fun tryStep(position: GridPosition, direction: Facing): Boolean {
        if (!canMoveTo(position, direction)) return false
        position.cellX += direction.dx
        position.cellY += direction.dy
        return true
    }
}
