package com.chthonic.dungeoncrawler.app

import androidx.compose.ui.input.key.Key
import com.chthonic.dungeoncrawler.tilemap.GridPosition
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.Kubriko
import com.pandulapeter.kubriko.keyboardInput.KeyboardInputAware
import com.pandulapeter.kubriko.manager.ActorManager
import com.pandulapeter.kubriko.manager.Manager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerManager(
    private val viewer: GridPosition,
    private val tileMapManager: TileMapManager,
    private val onViewerChanged: () -> Unit = {},
    isLoggingEnabled: Boolean = false,
    instanceNameForLogging: String? = null,
) : Manager(
    isLoggingEnabled = isLoggingEnabled,
    instanceNameForLogging = instanceNameForLogging,
    classNameForLogging = "PlayerManager",
), KeyboardInputAware {

    private val actorManager by manager<ActorManager>()

    // Lets the on-screen nav buttons mirror keyboard key-hold state (same button lights up
    // whether the user is holding W or pressing the on-screen forward button).
    private val _pressedActions = MutableStateFlow<Set<NavAction>>(emptySet())
    val pressedActions: StateFlow<Set<NavAction>> = _pressedActions.asStateFlow()

    override fun onInitialize(kubriko: Kubriko) {
        actorManager.add(this)
    }

    override fun onKeyPressed(key: Key) {
        key.toNavAction()?.let { _pressedActions.value += it }
        when (key) {
            Key.W -> moveForward()
            Key.S -> moveBackward()
            Key.A -> strafeLeft()
            Key.D -> strafeRight()
            Key.Q -> turnLeft()
            Key.E -> turnRight()
        }
    }

    override fun onKeyReleased(key: Key) {
        key.toNavAction()?.let { _pressedActions.value -= it }
    }

    private fun Key.toNavAction(): NavAction? = when (this) {
        Key.W -> NavAction.Forward
        Key.S -> NavAction.Backward
        Key.A -> NavAction.StrafeLeft
        Key.D -> NavAction.StrafeRight
        Key.Q -> NavAction.TurnLeft
        Key.E -> NavAction.TurnRight
        else -> null
    }

    fun moveForward() {
        if (tileMapManager.moveForward(viewer)) { logPosition("moveForward"); onViewerChanged() }
    }

    fun moveBackward() {
        if (tileMapManager.moveBackward(viewer)) { logPosition("moveBackward"); onViewerChanged() }
    }

    fun strafeLeft() {
        if (tileMapManager.strafeLeft(viewer)) { logPosition("strafeLeft"); onViewerChanged() }
    }

    fun strafeRight() {
        if (tileMapManager.strafeRight(viewer)) { logPosition("strafeRight"); onViewerChanged() }
    }

    fun turnLeft() {
        viewer.facing = viewer.facing.turnedLeft()
        logFacing("turnLeft")
        onViewerChanged()
    }

    fun turnRight() {
        viewer.facing = viewer.facing.turnedRight()
        logFacing("turnRight")
        onViewerChanged()
    }

    private fun logPosition(action: String) =
        log(action, "(${viewer.cellX}, ${viewer.cellY}) facing ${viewer.facing}")

    private fun logFacing(action: String) =
        log(action, "facing ${viewer.facing}")
}
