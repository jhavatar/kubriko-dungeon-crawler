package com.chthonic.dungeoncrawler.renderer

import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.GridPosition
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.manager.ActorManager
import com.pandulapeter.kubriko.manager.Manager

/**
 * Projects [viewer]'s [GridPosition] against the active [TileMapManager] and keeps the
 * corresponding wall/floor/ceiling actors in sync. Currently only renders the single wall slot
 * directly ahead; the full fixed-frustum slot table is the next step.
 */
class DungeonRendererManager(
    private val viewer: GridPosition,
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
    private var forwardWallActor: ForwardWallActor? = null
    private var lastCellX: Int? = null
    private var lastCellY: Int? = null
    private var lastFacing: Facing? = null

    override fun onUpdate(deltaTimeInMilliseconds: Int) {
        if (viewer.cellX == lastCellX && viewer.cellY == lastCellY && viewer.facing == lastFacing) {
            return
        }
        lastCellX = viewer.cellX
        lastCellY = viewer.cellY
        lastFacing = viewer.facing
        val isWallAhead = !tileMapManager.canMoveTo(viewer)
        if (isWallAhead && forwardWallActor == null) {
            val actor = ForwardWallActor(renderMode = { renderMode })
            forwardWallActor = actor
            actorManager.add(actor)
        } else if (!isWallAhead) {
            forwardWallActor?.let { actorManager.remove(it) }
            forwardWallActor = null
        }
    }
}
