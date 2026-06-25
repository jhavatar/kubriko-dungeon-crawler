package com.chthonic.dungeoncrawler.renderer

import com.chthonic.dungeoncrawler.tilemap.CellType
import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.GridPosition
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.manager.ActorManager
import com.pandulapeter.kubriko.manager.Manager

class DungeonRendererManager(
    private val viewer: GridPosition,
    val fovWidth: Int = 3,
    val viewDistance: Int = 3,
    var renderMode: RenderMode = RenderMode.TEXTURED,
    isLoggingEnabled: Boolean = false,
    instanceNameForLogging: String? = null,
) : Manager(
    isLoggingEnabled = isLoggingEnabled,
    instanceNameForLogging = instanceNameForLogging,
    classNameForLogging = "DungeonRendererManager",
) {
    private val tileMapManager by manager<TileMapManager>()
    private val actorManager by manager<ActorManager>()

    private val wallActors = mutableMapOf<Pair<Int, Int>, WallSlotActor>()
    private var lastCellX: Int? = null
    private var lastCellY: Int? = null
    private var lastFacing: Facing? = null

    // Half-size of the full viewport in scene units; slots are sized proportionally to this.
    private val viewportHalf = 300f

    override fun onUpdate(deltaTimeInMilliseconds: Int) {
        if (viewer.cellX == lastCellX && viewer.cellY == lastCellY && viewer.facing == lastFacing) return
        lastCellX = viewer.cellX
        lastCellY = viewer.cellY
        lastFacing = viewer.facing
        updateSlots()
    }

    private fun updateSlots() {
        val right = viewer.facing.turnedRight()

        // Determine which slots should have a wall actor
        val desiredWalls = mutableSetOf<Pair<Int, Int>>()
        for (depth in 1..viewDistance) {
            for (col in -(fovWidth / 2)..(fovWidth / 2)) {
                val cellX = viewer.cellX + depth * viewer.facing.dx + col * right.dx
                val cellY = viewer.cellY + depth * viewer.facing.dy + col * right.dy
                if (tileMapManager.tileMap.cellTypeAt(cellX, cellY) == CellType.WALL) {
                    desiredWalls.add(Pair(col, depth))
                }
            }
        }

        // Remove actors for slots that are no longer walls
        val keysToRemove = wallActors.keys.filter { it !in desiredWalls }
        keysToRemove.forEach { key ->
            wallActors.remove(key)?.let { actorManager.remove(it) }
        }

        // Add actors for newly-walled slots
        desiredWalls.filter { it !in wallActors }.forEach { key ->
            val (col, depth) = key
            val halfH = viewportHalf * (viewDistance - depth + 1f) / viewDistance
            val slotHeight = halfH * 2f
            val slotWidth = slotHeight / fovWidth
            val actor = WallSlotActor(
                centerX = col * slotWidth,
                width = slotWidth,
                height = slotHeight,
                depth = depth,
                viewDistance = viewDistance,
                renderMode = { renderMode },
                // Farther slots use lower layerIndex so nearer walls render on top
                layerIndex = viewDistance - depth,
            )
            wallActors[key] = actor
            actorManager.add(actor)
        }
    }
}
