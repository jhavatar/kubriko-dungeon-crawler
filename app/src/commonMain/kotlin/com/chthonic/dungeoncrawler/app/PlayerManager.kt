package com.chthonic.dungeoncrawler.app

import androidx.compose.ui.input.key.Key
import com.chthonic.dungeoncrawler.tilemap.GridPosition
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.Kubriko
import com.pandulapeter.kubriko.keyboardInput.KeyboardInputAware
import com.pandulapeter.kubriko.manager.ActorManager
import com.pandulapeter.kubriko.manager.Manager

class PlayerManager(
    private val viewer: GridPosition,
    private val tileMapManager: TileMapManager,
    isLoggingEnabled: Boolean = false,
    instanceNameForLogging: String? = null,
) : Manager(
    isLoggingEnabled = isLoggingEnabled,
    instanceNameForLogging = instanceNameForLogging,
    classNameForLogging = "PlayerManager",
), KeyboardInputAware {

    private val actorManager by manager<ActorManager>()

    override fun onInitialize(kubriko: Kubriko) {
        actorManager.add(this)
    }

    override fun onKeyPressed(key: Key) {
        when (key) {
            Key.W -> if (tileMapManager.moveForward(viewer)) logPosition("moveForward")
            Key.S -> if (tileMapManager.moveBackward(viewer)) logPosition("moveBackward")
            Key.A -> if (tileMapManager.strafeLeft(viewer)) logPosition("strafeLeft")
            Key.D -> if (tileMapManager.strafeRight(viewer)) logPosition("strafeRight")
            Key.Q -> { viewer.facing = viewer.facing.turnedLeft(); logFacing("turnLeft") }
            Key.E -> { viewer.facing = viewer.facing.turnedRight(); logFacing("turnRight") }
        }
    }

    private fun logPosition(action: String) =
        log(action, "(${viewer.cellX}, ${viewer.cellY}) facing ${viewer.facing}")

    private fun logFacing(action: String) =
        log(action, "facing ${viewer.facing}")
}
