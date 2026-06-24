package com.chthonic.dungeoncrawler.tilemap

import com.pandulapeter.kubriko.manager.Manager

/**
 * Owns the [TileMap] for the active dungeon level and resolves move/turn legality against it.
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

    fun canMoveTo(position: GridPosition, facing: Facing = position.facing): Boolean {
        val targetX = position.cellX + facing.dx
        val targetY = position.cellY + facing.dy
        if (!tileMap.isInBounds(targetX, targetY)) {
            return false
        }
        return when (facing) {
            Facing.NORTH -> !tileMap.hasWallNorth(position.cellX, position.cellY)
            Facing.SOUTH -> !tileMap.hasWallSouth(position.cellX, position.cellY)
            Facing.EAST -> !tileMap.hasWallEast(position.cellX, position.cellY)
            Facing.WEST -> !tileMap.hasWallWest(position.cellX, position.cellY)
        }
    }

    fun moveForward(position: GridPosition): Boolean = tryStep(position, position.facing)

    fun moveBackward(position: GridPosition): Boolean = tryStep(position, position.facing.opposite())

    private fun tryStep(position: GridPosition, direction: Facing): Boolean {
        if (!canMoveTo(position, direction)) {
            return false
        }
        position.cellX += direction.dx
        position.cellY += direction.dy
        return true
    }
}
