package com.chthonic.dungeoncrawler.app

import com.chthonic.dungeoncrawler.renderer.Monster
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.manager.Manager

// Drives monster AI (movement timing + animation frame advancement) each engine tick.
// Movement is forward/backward only, per docs/MapConventions.md ("monsters can only move
// forwards and backwards") — never strafeLeft/strafeRight, unlike PlayerManager.
//
// Manager subclasses already receive onUpdate(deltaTimeInMilliseconds) ticks from the engine
// automatically (see TileMapManager, DungeonRendererManager) — no Dynamic trait or
// ActorManager registration is needed here, unlike PlayerManager's KeyboardInputAware
// registration (which exists for a different reason: receiving key-event callbacks).
class MonsterManager(
    private val monsters: List<Monster>,
    private val tileMapManager: TileMapManager,
    private val onMonstersChanged: () -> Unit = {},
    isLoggingEnabled: Boolean = false,
    instanceNameForLogging: String? = null,
) : Manager(
    isLoggingEnabled = isLoggingEnabled,
    instanceNameForLogging = instanceNameForLogging,
    classNameForLogging = "MonsterManager",
) {

    // Per-monster elapsed time since its last move attempt — separate from each monster's own
    // animation-frame timer (Monster.frameElapsedMs).
    private val moveElapsedMs = HashMap<Monster, Int>()

    override fun onUpdate(deltaTimeInMilliseconds: Int) {
        var anyChanged = false
        for (monster in monsters) {
            if (tickAnimation(monster, deltaTimeInMilliseconds)) anyChanged = true
            if (tickMovement(monster, deltaTimeInMilliseconds)) anyChanged = true
        }
        if (anyChanged) onMonstersChanged()
    }

    private fun tickAnimation(monster: Monster, deltaTimeInMilliseconds: Int): Boolean {
        val frameCount = monster.currentAnimation.front.size
        if (frameCount <= 1) return false
        monster.frameElapsedMs += deltaTimeInMilliseconds
        if (monster.frameElapsedMs < monster.frameDurationMs) return false
        monster.frameElapsedMs = 0
        monster.frameIndex = (monster.frameIndex + 1) % frameCount
        return true
    }

    // Simple patrol: walk forward on a fixed interval; if blocked, turn to face back the way
    // it came and try again next tick. Deliberately minimal — anything beyond basic
    // forward/backward movement (chasing, fleeing, combat) is out of scope for a first pass,
    // see docs/MonsterImplementationPlan.md "Deferred / out of scope for a first pass".
    private fun tickMovement(monster: Monster, deltaTimeInMilliseconds: Int): Boolean {
        val elapsed = (moveElapsedMs[monster] ?: 0) + deltaTimeInMilliseconds
        if (elapsed < MOVE_INTERVAL_MS) {
            moveElapsedMs[monster] = elapsed
            return false
        }
        moveElapsedMs[monster] = 0
        if (tileMapManager.moveForward(monster.mob)) return true
        monster.mob.facing = monster.mob.facing.opposite()
        return true
    }

    private companion object {
        const val MOVE_INTERVAL_MS = 900
    }
}
