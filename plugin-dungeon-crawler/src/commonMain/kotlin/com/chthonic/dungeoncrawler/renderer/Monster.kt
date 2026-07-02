package com.chthonic.dungeoncrawler.renderer

import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.Mob

// A monster occupying a single TileMap cell. Position/facing live on `mob` (the same
// GridPosition model the player party uses, see Mob) — everything else here is rendering/
// animation state specific to monsters.
class Monster(
    val mob: Mob,
    var currentAnimation: MonsterAnimation,
    var frameIndex: Int = 0,
    var frameElapsedMs: Int = 0,
    val frameDurationMs: Int = 150,
    // Fraction of the cell's own angular width at depth D — see
    // DungeonRendererManager.buildDrawCommands "Step 4 — monster sprites at depth D".
    val spriteWidthFraction: Float = 0.6f,
    // Fraction of the depth-D wall slot height.
    val spriteHeightFraction: Float = 0.75f,
) {
    // Dirty-check bookkeeping for DungeonRendererManager, mirroring the viewer's own
    // lastCellX/lastCellY/lastFacing fields. See docs/MonsterImplementationPlan.md
    // "Triggering a rebuild on monster movement".
    internal var lastCellX: Int? = null
    internal var lastCellY: Int? = null
    internal var lastFacing: Facing? = null
    internal var lastFrameIndex: Int? = null
    internal var wasPotentiallyVisible: Boolean = false
}
