package com.chthonic.dungeoncrawler.renderer

import com.chthonic.dungeoncrawler.tilemap.CellType
import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.GridPosition
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.manager.ActorManager
import com.pandulapeter.kubriko.manager.Manager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.pandulapeter.kubriko.manager.ViewportManager

class DungeonRendererManager(
    private val viewer: GridPosition,
    val fovWidth: Int = 4,
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

    // The set of (col, depth) frustum-slot coordinates that currently have a front wall actor.
    // Exposed as Compose State so the minimap and viewport overlays recompose automatically.
    private val _desiredWalls = mutableStateOf<Set<Pair<Int, Int>>>(emptySet())
    val desiredWalls: State<Set<Pair<Int, Int>>> = _desiredWalls

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

    // Determines which front-facing wall rectangles are visible and keeps their WallSlotActors
    // in sync. Called every frame when the viewer position/facing or viewport size changes.
    private fun updateSlots(viewW: Float, viewH: Float) {
        log("updateSlots")
        val right = viewer.facing.turnedRight()
        // Build the set of (col, depth) pairs that should have a WallSlotActor this frame.
        val newWalls = mutableSetOf<Pair<Int, Int>>()

        // Each column is a vertical strip of the viewport centred on col * slotWidth.
        for (col in -(fovWidth / 2)..(fovWidth / 2)) {
            val colLeft = col - 0.5f
            val colRight = col + 0.5f
            // The widest frustum interval this column could ever expose, capped to ±fovHalf.
            // Columns entirely outside the frustum (e.g. col=±3 with fovWidth=4) are skipped.
            val maxExposable = maxOf(colLeft, -fovHalf) to minOf(colRight, fovHalf)
            if (maxExposable.first >= maxExposable.second) continue

            // Accumulates covered frustum intervals as walls are found near-to-far, so that
            // cells hidden behind a closer wall in the same column are not rendered.
            val covered = mutableListOf<Pair<Float, Float>>()

            // depth=0 is the party's own row: always skipped here. The party's cell (col=0) is
            // OPEN by definition, and side cells (col≠0) at depth=0 are walls beside the party
            // that belong to side-wall rendering (updateSideWalls), not forward occlusion.
            // At depth≥1: geometric frustum — visible half-width = depth * fovHalf / viewDistance.
            for (depth in 1..viewDistance) {
                // How wide the frustum is at this depth — outer columns only become visible
                // once the frustum has opened far enough to reach them.
                val visibleHalfCols = depth * fovHalf / viewDistance
                val visLeft = maxOf(colLeft, -visibleHalfCols)
                val visRight = minOf(colRight, visibleHalfCols)
                if (visLeft >= visRight) continue  // column not yet inside the frustum at this depth

                // Only check the cell if part of its frustum interval is not already behind a
                // known wall.
                val uncovered = subtractCoverage(visLeft to visRight, covered)
                if (uncovered.isNotEmpty()) {
                    val cellX = viewer.cellX + depth * viewer.facing.dx + col * right.dx
                    val cellY = viewer.cellY + depth * viewer.facing.dy + col * right.dy
                    if (tileMapManager.tileMap.cellTypeAt(cellX, cellY) == CellType.WALL) {
                        log("updateSlots", "add wal $cellX, $cellY")
                        newWalls.add(col to depth)
                        // Mark this interval as covered so nothing behind it is rendered.
                        mergeInto(covered, visLeft to visRight)
                    }
                }

                // Early exit: if every part of this column that could ever be visible is already
                // covered by closer walls, there is nothing more to find at greater depths.
                if (subtractCoverage(maxExposable, covered).isEmpty()) break
            }
        }

        // Publish the new set so Compose overlays recompose immediately.
        _desiredWalls.value = newWalls

        // Remove actors for (col, depth) pairs that are no longer visible.
        val keysToRemove = wallActors.keys.filter { it !in newWalls }
        keysToRemove.forEach { key ->
            wallActors.remove(key)?.let { actorManager.remove(it) }
        }

        // Create actors for newly visible (col, depth) pairs.
        // Slot dimensions follow the perspective projection: at depth D with fovWidth=viewDistance,
        // each slot is viewW/fovWidth wide and viewH/D tall, centred at col * slotWidth.
        // layerIndex: closer walls use a higher index so they paint on top of farther ones;
        // the +1 keeps front walls above same-depth side walls from updateSideWalls.
        newWalls.filter { it !in wallActors }.forEach { key ->
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

    // Determines which side-wall trapezoids are visible and keeps their SideWallSlotActors
    // in sync. A side wall is the face running parallel to the viewing direction that appears
    // whenever an open cell and a wall cell share a column boundary. Called every frame alongside
    // updateSlots when the viewer position/facing or viewport size changes.
    private fun updateSideWalls(viewW: Float, viewH: Float) {
        log("updateSideWalls")
        val right = viewer.facing.turnedRight()
        // Build the set of (k, depth) pairs that should have a SideWallSlotActor this frame.
        val desiredSideWalls = mutableSetOf<Pair<Int, Int>>()

        // k is colBoundaryTimes2: the boundary between leftCol=(k-1)/2 and rightCol=(k+1)/2
        // sits at frustum x = k/2. For fovWidth=4: k ∈ {-3,-1,+1,+3}, boundaries at ±0.5, ±1.5.
        // A side wall exists at boundary k, depth D when one of the two flanking cells is a wall
        // and the other is open — that open/wall transition is the visible face.
        for (k in -(fovWidth - 1)..(fovWidth - 1) step 2) {
            val leftCol = (k - 1) / 2
            val rightCol = (k + 1) / 2
            // depth=0 covers the party's own row (walls immediately beside the party);
            // depth=viewDistance-1 is the last depth strip before the view limit.
            for (depth in 0 until viewDistance) {
                val leftCellX = viewer.cellX + depth * viewer.facing.dx + leftCol * right.dx
                val leftCellY = viewer.cellY + depth * viewer.facing.dy + leftCol * right.dy
                val rightCellX = viewer.cellX + depth * viewer.facing.dx + rightCol * right.dx
                val rightCellY = viewer.cellY + depth * viewer.facing.dy + rightCol * right.dy
                val leftIsWall = tileMapManager.tileMap.cellTypeAt(leftCellX, leftCellY) == CellType.WALL
                val rightIsWall = tileMapManager.tileMap.cellTypeAt(rightCellX, rightCellY) == CellType.WALL
                // Only one side of the boundary is a wall → visible face.
                if (leftIsWall != rightIsWall) {
                    log("updateSideWalls", "add wall $k, $depth")
                    desiredSideWalls.add(k to depth)
                }
            }
        }

        // Remove actors for boundaries that are no longer visible.
        val keysToRemove = sideWallActors.keys.filter { it !in desiredSideWalls }
        keysToRemove.forEach { key -> sideWallActors.remove(key)?.let { actorManager.remove(it) } }

        // Create actors for newly visible boundaries.
        // Each side wall is a depth strip: a trapezoid whose near edge is at (xNear, ±yNearHalf)
        // and far edge at (xFar, ±yFarHalf), both derived from the perspective projection.
        // At depth=0, xNear is clamped to the screen edge (±viewW/2) because the formula
        // xB * viewW * viewDistance / (fovWidth * depth) diverges as depth → 0.
        // layerIndex: same depth as a front wall but one step lower, so front walls paint on top.
        desiredSideWalls.filter { it !in sideWallActors }.forEach { key ->
            val (k, depth) = key
            val xB = k / 2f
            val xNear = if (depth == 0) {
                // Wall immediately beside the party: near edge reaches the screen edge.
                if (xB > 0f) viewW / 2f else -viewW / 2f
            } else {
                xB * viewW * viewDistance / (fovWidth * depth)
            }
            // Far edge is always one depth step further using the standard perspective formula.
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
