package com.chthonic.dungeoncrawler.renderer

import com.chthonic.dungeoncrawler.tilemap.CellType
import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.GridPosition
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.manager.ActorManager
import com.pandulapeter.kubriko.manager.Manager
import com.pandulapeter.kubriko.manager.ViewportManager

class DungeonRendererManager(
    private val viewer: GridPosition,
    val fovWidth: Int = 5,
    val viewDistance: Int = 4,
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
    private val viewportManager by manager<ViewportManager>()

    private val wallActors = mutableMapOf<Pair<Int, Int>, WallSlotActor>()
    private val sideWallActors = mutableMapOf<Pair<Int, Int>, SideWallSlotActor>()
    private var lastCellX: Int? = null
    private var lastCellY: Int? = null
    private var lastFacing: Facing? = null
    private var lastViewW: Float = 0f
    private var lastViewH: Float = 0f

    private val fovHalf = fovWidth / 2f

    override fun onUpdate(deltaTimeInMilliseconds: Int) {
        val viewW = (viewportManager.bottomRight.value.x - viewportManager.topLeft.value.x).raw
        val viewH = (viewportManager.bottomRight.value.y - viewportManager.topLeft.value.y).raw
        if (viewW == 0f || viewH == 0f) return

        val viewChanged = viewW != lastViewW || viewH != lastViewH
        val positionChanged = viewer.cellX != lastCellX || viewer.cellY != lastCellY || viewer.facing != lastFacing
        if (!positionChanged && !viewChanged) return

        if (viewChanged) {
            wallActors.values.forEach { actorManager.remove(it) }
            wallActors.clear()
            sideWallActors.values.forEach { actorManager.remove(it) }
            sideWallActors.clear()
        }

        lastCellX = viewer.cellX
        lastCellY = viewer.cellY
        lastFacing = viewer.facing
        lastViewW = viewW
        lastViewH = viewH
        log("onUpdate")
        updateSlots(viewW, viewH)
        updateSideWalls(viewW, viewH)
    }

    private fun updateSlots(viewW: Float, viewH: Float) {
        log("updateSlots")
        val right = viewer.facing.turnedRight()
        val desiredWalls = mutableSetOf<Pair<Int, Int>>()

        for (col in -(fovWidth / 2)..(fovWidth / 2)) {
            val colLeft = col - 0.5f
            val colRight = col + 0.5f
            // Maximum interval this column can ever expose (capped to the frustum at max depth)
            val maxExposable = maxOf(colLeft, -fovHalf) to minOf(colRight, fovHalf)
            if (maxExposable.first >= maxExposable.second) continue

            // Covered intervals accumulated as walls are found near-to-far within this column
            val covered = mutableListOf<Pair<Float, Float>>()

            // depth=0 is the party's own row: always skipped here. The party's cell (col=0) is
            // OPEN by definition, and side cells (col≠0) at depth=0 are walls beside the party
            // that belong to side-wall rendering (updateSideWalls), not forward occlusion.
            // At depth≥1: geometric frustum — visible half-width = depth * fovHalf / viewDistance.
            for (depth in 1..viewDistance) {
                val visibleHalfCols = depth * fovHalf / viewDistance
                val visLeft = maxOf(colLeft, -visibleHalfCols)
                val visRight = minOf(colRight, visibleHalfCols)
                if (visLeft >= visRight) continue  // column not yet inside the frustum

                val uncovered = subtractCoverage(visLeft to visRight, covered)
                if (uncovered.isNotEmpty()) {
                    val cellX = viewer.cellX + depth * viewer.facing.dx + col * right.dx
                    val cellY = viewer.cellY + depth * viewer.facing.dy + col * right.dy
                    if (tileMapManager.tileMap.cellTypeAt(cellX, cellY) == CellType.WALL) {
                        log("updateSlots", "add wal $cellX, $cellY")
                        desiredWalls.add(col to depth)
                        mergeInto(covered, visLeft to visRight)
                    }
                }

                // Nothing more can ever be exposed in this column; no point looking deeper
                if (subtractCoverage(maxExposable, covered).isEmpty()) break
            }
        }

        // Remove actors for slots no longer needed
        val keysToRemove = wallActors.keys.filter { it !in desiredWalls }
        keysToRemove.forEach { key ->
            wallActors.remove(key)?.let { actorManager.remove(it) }
        }

        // Add actors for newly-walled slots.
        // Cubic cells: slot height = viewH/depth, slot width = viewW*viewDistance/(fovWidth*depth).
        // At depth=1 with fovWidth=viewDistance, col=0 fills the entire viewport.
        desiredWalls.filter { it !in wallActors }.forEach { key ->
            val (col, depth) = key
            val slotHeight = viewH / depth
            val slotWidth = viewW * viewDistance / (fovWidth * depth)
            val actor = WallSlotActor(
                centerX = col * slotWidth,
                width = slotWidth,
                height = slotHeight,
                depth = depth,
                viewDistance = viewDistance,
                renderMode = { renderMode },
                layerIndex = (viewDistance - depth) * 2 + 1,
            )
            wallActors[key] = actor
            actorManager.add(actor)
        }
    }

    private fun updateSideWalls(viewW: Float, viewH: Float) {
        log("updateSideWalls")
        val right = viewer.facing.turnedRight()
        val desiredSideWalls = mutableSetOf<Pair<Int, Int>>()

        // Iterate over every col boundary within the FOV.
        // k is colBoundaryTimes2: boundary at x = k/2 lies between leftCol=(k-1)/2 and rightCol=(k+1)/2.
        // For fovWidth=4: k ∈ {-3,-1,1,3} → boundaries at {-1.5,-0.5,0.5,1.5}.
        for (k in -(fovWidth - 1)..(fovWidth - 1) step 2) {
            val leftCol = (k - 1) / 2
            val rightCol = (k + 1) / 2
            for (depth in 0 until viewDistance) {
                val leftCellX = viewer.cellX + depth * viewer.facing.dx + leftCol * right.dx
                val leftCellY = viewer.cellY + depth * viewer.facing.dy + leftCol * right.dy
                val rightCellX = viewer.cellX + depth * viewer.facing.dx + rightCol * right.dx
                val rightCellY = viewer.cellY + depth * viewer.facing.dy + rightCol * right.dy
                val leftIsWall = tileMapManager.tileMap.cellTypeAt(leftCellX, leftCellY) == CellType.WALL
                val rightIsWall = tileMapManager.tileMap.cellTypeAt(rightCellX, rightCellY) == CellType.WALL
                if (leftIsWall != rightIsWall) {
                    log("updateSideWalls", "add wall $k, $depth")
                    desiredSideWalls.add(k to depth)
                }
            }
        }

        val keysToRemove = sideWallActors.keys.filter { it !in desiredSideWalls }
        keysToRemove.forEach { key -> sideWallActors.remove(key)?.let { actorManager.remove(it) } }

        desiredSideWalls.filter { it !in sideWallActors }.forEach { key ->
            val (k, depth) = key
            val xB = k / 2f
            // Near edge: at depth=0 the wall reaches the screen edge; otherwise use perspective formula.
            val xNear = if (depth == 0) {
                if (xB > 0f) viewW / 2f else -viewW / 2f
            } else {
                xB * viewW * viewDistance / (fovWidth * depth)
            }
            val xFar = xB * viewW * viewDistance / (fovWidth * (depth + 1))
            val yNearHalf = if (depth == 0) viewH / 2f else viewH / (2f * depth)
            val yFarHalf = viewH / (2f * (depth + 1))
            val actor = SideWallSlotActor(
                xNear = xNear,
                xFar = xFar,
                yNearHalf = yNearHalf,
                yFarHalf = yFarHalf,
                depth = depth,
                viewDistance = viewDistance,
                renderMode = { renderMode },
                layerIndex = (viewDistance - depth) * 2,
            )
            sideWallActors[key] = actor
            actorManager.add(actor)
        }
    }

    // Returns the portions of [interval] not covered by any interval in [covered]
    private fun subtractCoverage(
        interval: Pair<Float, Float>,
        covered: List<Pair<Float, Float>>,
    ): List<Pair<Float, Float>> {
        var remaining = listOf(interval)
        for ((coverLeft, coverRight) in covered) {
            remaining = remaining.flatMap { (left, right) ->
                buildList {
                    if (left < coverLeft) add(left to minOf(right, coverLeft))
                    if (right > coverRight) add(maxOf(left, coverRight) to right)
                }.filter { (l, r) -> l < r }
            }
        }
        return remaining
    }

    // Adds [interval] to [covered] and merges any overlapping or touching intervals
    private fun mergeInto(
        covered: MutableList<Pair<Float, Float>>,
        interval: Pair<Float, Float>,
    ) {
        covered.add(interval)
        covered.sortBy { it.first }
        val merged = mutableListOf<Pair<Float, Float>>()
        for (it in covered) {
            if (merged.isEmpty() || merged.last().second < it.first) {
                merged.add(it)
            } else {
                merged[merged.size - 1] = merged.last().first to maxOf(merged.last().second, it.second)
            }
        }
        covered.clear()
        covered.addAll(merged)
    }
}
