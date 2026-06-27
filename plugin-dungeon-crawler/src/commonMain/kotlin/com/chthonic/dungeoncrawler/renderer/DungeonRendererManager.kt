package com.chthonic.dungeoncrawler.renderer

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
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
    private val textMeasurer: TextMeasurer? = null,
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

    private val wallActors = mutableMapOf<Pair<Int, Int>, FrontWallActor>()
    private val sideWallActors = mutableMapOf<Pair<Int, Int>, SideWallActor>()
    private var lastCellX: Int? = null
    private var lastCellY: Int? = null
    private var lastFacing: Facing? = null
    private var lastViewW: Float = 0f
    private var lastViewH: Float = 0f

    private val fovHalf = fovWidth / 2f

    // Map from (mapCellX, mapCellY) → debug label for each visible front wall cell.
    // Coordinates are resolved in map space at compute time so the minimap can use them directly.
    private val _frontWallCells = mutableStateOf<Map<Pair<Int, Int>, String>>(emptyMap())
    val frontWallCells: State<Map<Pair<Int, Int>, String>> = _frontWallCells

    // Map from (mapCellX, mapCellY) → debug label for each visible side wall cell.
    private val _sideWallCells = mutableStateOf<Map<Pair<Int, Int>, String>>(emptyMap())
    val sideWallCells: State<Map<Pair<Int, Int>, String>> = _sideWallCells

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
        updateFrontWalls(viewW, viewH)
        updateSideWalls(viewW, viewH)
    }

    // Determines which front-facing wall rectangles are visible and keeps their FrontWallActors
    // in sync. Called every frame when the viewer position/facing or viewport size changes.
    private fun updateFrontWalls(viewW: Float, viewH: Float) {
        log("updateFrontWalls")
        val right = viewer.facing.turnedRight()
        // Build the set of (lat, depth) pairs that should have a FrontWallActor this frame,
        // and a parallel map of map-space cell coordinates for the minimap overlay.
        val newWalls = mutableSetOf<Pair<Int, Int>>()
        val newFrontWallCells = mutableMapOf<Pair<Int, Int>, String>()

        // Each lat is a lateral screen-space offset from centre: lat=0 is straight ahead, positive
        // lats are to the viewer's right, negative to the left — independent of which map direction that is.
        for (lat in -(fovWidth / 2)..(fovWidth / 2)) {
            val latLeft = lat - 0.5f
            val latRight = lat + 0.5f
            // Skip laterals entirely outside the frustum (e.g. lat=±3 with fovWidth=4).
            if (maxOf(latLeft, -fovHalf) >= minOf(latRight, fovHalf)) continue

            // Scan depths near-to-far. A front face is only visible at an open→wall transition:
            // the cell is a wall and the cell directly in front of it (depth-1) is open.
            // Walls preceded by another wall have no exposed face and are skipped.
            for (depth in 1..viewDistance) {
                val visibleLatHalf = depth * fovHalf / viewDistance
                if (maxOf(latLeft, -visibleLatHalf) >= minOf(latRight, visibleLatHalf)) continue

                val cellX = viewer.cellX + depth * viewer.facing.dx + lat * right.dx
                val cellY = viewer.cellY + depth * viewer.facing.dy + lat * right.dy
                if (tileMapManager.tileMap.cellTypeAt(cellX, cellY) != CellType.WALL) continue

                val prevCellX = viewer.cellX + (depth - 1) * viewer.facing.dx + lat * right.dx
                val prevCellY = viewer.cellY + (depth - 1) * viewer.facing.dy + lat * right.dy
                if (tileMapManager.tileMap.cellTypeAt(prevCellX, prevCellY) == CellType.WALL) continue

                log("updateFrontWalls", "add wall $lat, $depth")
                newWalls.add(lat to depth)
                newFrontWallCells[cellX to cellY] = "$lat,$depth"
            }
        }

        // Publish map-space coordinates so the minimap can highlight cells without conversion.
        _frontWallCells.value = newFrontWallCells

        // Remove actors for (lat, depth) pairs that are no longer visible.
        val keysToRemove = wallActors.keys.filter { it !in newWalls }
        keysToRemove.forEach { key ->
            wallActors.remove(key)?.let { actorManager.remove(it) }
        }

        // Create actors for newly visible (lat, depth) pairs.
        // Slot dimensions follow the perspective projection: at depth D with fovWidth=viewDistance,
        // each slot is viewW/fovWidth wide and viewH/D tall, centred at lat * slotWidth.
        // layerIndex: closer walls use a higher index so they paint on top of farther ones;
        // the +1 keeps front walls above same-depth side walls from updateSideWalls.
        newWalls.filter { it !in wallActors }.forEach { key ->
            val (lat, dep) = key
            val slotHeight = viewH / dep
            val slotWidth = viewW * viewDistance / (fovWidth * dep)
            val actor = FrontWallActor(
                centerX = lat * slotWidth,
                width = slotWidth,
                height = slotHeight,
                depth = dep,
                viewDistance = viewDistance,
                renderMode = { renderMode },
                layerIndex = (viewDistance - dep) * 2 + 1,
                debugLabel = textMeasurer?.measure(
                    "$lat,$dep",
                    style = TextStyle(fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.Yellow),
                ),
            )
            wallActors[key] = actor
            actorManager.add(actor)
        }
    }

    // Determines which side-wall trapezoids are visible and keeps their SideWallActors
    // in sync. A side wall is the face running parallel to the viewing direction that appears
    // whenever an open cell and a wall cell share a lateral boundary. Called every frame alongside
    // updateFrontWalls when the viewer position/facing or viewport size changes.
    private fun updateSideWalls(viewW: Float, viewH: Float) {
        log("updateSideWalls")
        val right = viewer.facing.turnedRight()
        // Build the set of (k, depth) pairs that should have a SideWallActor this frame,
        // and a parallel map of map-space wall cell coordinates for the minimap overlay.
        val newSideWalls = mutableSetOf<Pair<Int, Int>>()
        val newSideWallCells = mutableMapOf<Pair<Int, Int>, String>()

        // k is latBoundaryTimes2: the boundary between leftLat=(k-1)/2 and rightLat=(k+1)/2
        // sits at frustum x = k/2. For fovWidth=4: k ∈ {-3,-1,+1,+3}, boundaries at ±0.5, ±1.5.
        // A side wall exists at boundary k, depth D when one of the two flanking cells is a wall
        // and the other is open — that open/wall transition is the visible face.
        val latMax = fovWidth / 2
        for (k in -(2 * latMax - 1)..(2 * latMax - 1) step 2) {
            val leftLat = (k - 1) / 2
            val rightLat = (k + 1) / 2
            // depth=0 covers the party's own row (walls immediately beside the party);
            // depth=viewDistance-1 is the last depth strip before the view limit.
            for (depth in 0 until viewDistance) {
                val leftCellX = viewer.cellX + depth * viewer.facing.dx + leftLat * right.dx
                val leftCellY = viewer.cellY + depth * viewer.facing.dy + leftLat * right.dy
                val rightCellX = viewer.cellX + depth * viewer.facing.dx + rightLat * right.dx
                val rightCellY = viewer.cellY + depth * viewer.facing.dy + rightLat * right.dy
                val leftIsWall = tileMapManager.tileMap.cellTypeAt(leftCellX, leftCellY) == CellType.WALL
                val rightIsWall = tileMapManager.tileMap.cellTypeAt(rightCellX, rightCellY) == CellType.WALL
                // Only one side of the boundary is a wall → visible face.
                if (leftIsWall != rightIsWall) {
                    // Pre-compute the near/far screen x to skip zero-width trapezoids.
                    // At depth=0, k=±1: xNear and xFar both equal ±viewW/2, so width=0 and
                    // SideWallActor.draw() would return early — no point creating the actor.
                    val xB = k / 2f
                    val xNear = if (depth == 0) (if (xB > 0f) viewW / 2f else -viewW / 2f)
                                else xB * viewW * viewDistance / (fovWidth * depth)
                    val xFar = xB * viewW * viewDistance / (fovWidth * (depth + 1))
                    if (xNear == xFar) continue
                    // Skip strips whose far edge (the one closer to screen centre) is already outside
                    // the frustum — the entire strip is off-screen.
                    if (xFar > viewW / 2f || xFar < -viewW / 2f) continue

                    val wallLat = if (leftIsWall) leftLat else rightLat
                    log("updateSideWalls", "add wall $wallLat, $depth")
                    newSideWalls.add(k to depth)
                    // Record the wall cell's map coordinates so the minimap needs no conversion.
                    val (wCellX, wCellY) = if (leftIsWall) leftCellX to leftCellY else rightCellX to rightCellY
                    newSideWallCells[wCellX to wCellY] = "$k,$depth"
                }
            }
        }

        // Publish map-space coordinates so the minimap can highlight cells without conversion.
        _sideWallCells.value = newSideWallCells

        // Remove actors for boundaries that are no longer visible.
        val keysToRemove = sideWallActors.keys.filter { it !in newSideWalls }
        keysToRemove.forEach { key -> sideWallActors.remove(key)?.let { actorManager.remove(it) } }

        // Create actors for newly visible boundaries.
        // Each side wall is a depth strip: a trapezoid whose near edge is at (xNear, ±yNearHalf)
        // and far edge at (xFar, ±yFarHalf), both derived from the perspective projection.
        // At depth=0, xNear is clamped to the screen edge (±viewW/2) because the formula
        // xB * viewW * viewDistance / (fovWidth * depth) diverges as depth → 0.
        // layerIndex: same depth as a front wall but one step lower, so front walls paint on top.
        newSideWalls.filter { it !in sideWallActors }.forEach { key ->
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
            // If the near edge overshoots the frustum boundary, clamp it and interpolate the height
            // at the clip point so the trapezoid meets the screen edge at the right size.
            val clampedXNear = xNear.coerceIn(-viewW / 2f, viewW / 2f)
            val clampedYNearHalf = if (clampedXNear == xNear || xFar == xNear) yNearHalf
                else yNearHalf + (clampedXNear - xNear) / (xFar - xNear) * (yFarHalf - yNearHalf)
            val actor = SideWallActor(
                xNear = clampedXNear,
                xFar = xFar,
                yNearHalf = clampedYNearHalf,
                yFarHalf = yFarHalf,
                depth = depth,
                viewDistance = viewDistance,
                renderMode = { renderMode },
                layerIndex = (viewDistance - depth) * 2,
                debugLabel = textMeasurer?.measure(
                    "$k,$depth",
                    style = TextStyle(fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.Cyan),
                ),
            )
            sideWallActors[key] = actor
            actorManager.add(actor)
        }
    }

}
