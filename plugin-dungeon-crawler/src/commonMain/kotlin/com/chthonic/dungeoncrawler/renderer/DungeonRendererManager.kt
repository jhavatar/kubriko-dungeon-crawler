package com.chthonic.dungeoncrawler.renderer

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
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
    // The entity whose position and facing drive the first-person view.
    private val viewer: GridPosition,
    // Controls the angular width of the frustum in cell-widths (fovHalf = fovWidth / 2).
    // Odd values: exactly fovWidth equal-width columns. Even values: (fovWidth - 1) full
    // columns plus two half-width edge columns; total angular coverage is still ±fovHalf.
    val fovWidth: Int = 5,
    // How many cells forward the frustum extends. Walls beyond this distance are not rendered.
    val viewDistance: Int = 4,
    // Fraction of the viewport height that a wall at depth=1 occupies (0 < scale ≤ 1).
    // Values below 1 leave floor and ceiling strips visible; 1.0 fills the full viewport.
    val wallHeightScale: Float = 0.8f,
    // Switches between solid-colour textured rendering and wireframe outline rendering.
    renderMode: RenderMode = RenderMode.TEXTURED,
    // When non-null, enables debug labels drawn on each wall strip showing its (lat,depth) or
    // (k,depth) slot coordinates.
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

    private var dungeonViewActor: DungeonViewActor? = null
    private var lastCellX: Int? = null
    private var lastCellY: Int? = null
    private var lastFacing: Facing? = null
    private var lastViewW: Float = 0f
    private var lastViewH: Float = 0f
    private var lastRenderMode: RenderMode? = null
    var renderMode: RenderMode = renderMode
        set(value) { field = value; lastRenderMode = null }

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
        val modeChanged = renderMode != lastRenderMode
        if (!positionChanged && !viewChanged && !modeChanged) return

        if (viewChanged || dungeonViewActor == null) {
            dungeonViewActor?.let { actorManager.remove(it) }
            val actor = DungeonViewActor(viewW, viewH, renderMode = { renderMode })
            dungeonViewActor = actor
            actorManager.add(actor)
        }

        lastCellX = viewer.cellX
        lastCellY = viewer.cellY
        lastFacing = viewer.facing
        lastViewW = viewW
        lastViewH = viewH
        lastRenderMode = renderMode
        log("onUpdate")
        dungeonViewActor?.drawCommands = updateWalls(viewW, viewH)
    }

    // Front-to-back angular occlusion traversal.
    //
    // Builds the complete list of DrawCommands for this frame: floor/ceiling depth bands
    // followed by all visible wall strips in front-to-back traversal order. The single
    // DungeonViewActor replays this list every frame — no actor churn, no layerIndex ordering.
    //
    // -----------------------------------------------------------------------------------------
    // Core invariant — angle = lateral / depth
    // -----------------------------------------------------------------------------------------
    // "Angle" here is the slope of a sight ray in the top-down plan view, not a trigonometric
    // angle. The party is at the origin; depth increases forward; lateral increases rightward.
    // Any straight ray from the origin has a constant slope = lateral/depth at every point:
    //
    //   depth                                        depth
    //     ↑                                            ↑
    //   4 ·  ·  ·  ·  X   lat=2,depth=4 → 2/4=0.5  4 ├──────────────┤  lat ±fovHalf
    //     |           ╱                               3 │╲            ╱│  (frustum at
    //   3 ·  ·  ·  X    lat=1.5,depth=3 → 1.5/3=0.5   │ ╲          ╱ │  viewDistance)
    //     |         ╱                               2   │  ╲        ╱  │
    //   2 ·  ·  X    lat=1,depth=2 → 1/2=0.5       1   │   ╲      ╱   │
    //     |       ╱                                 0   │    ╲    ╱    │
    //   1 ·  X    lat=0.5,depth=1 → 0.5/1=0.5              *          party
    //     |   ╱                                        ←  fovHalf  →
    //   0 ·  *──────────────→ lateral              frustumAngleHalf = fovHalf/viewDistance
    //        0   1   2
    //
    // The frustum spans angles ±frustumAngleHalf. Every feature is expressed as an interval
    // on this axis; the covered list tracks which sub-intervals are already blocked.
    //
    // Front wall at (lat, D) — cell spans lateral [lat-0.5, lat+0.5] at depth D:
    //
    //   depth           left ray  right ray
    //     ↑                  ╲    ╱
    //   2 ·  · [══wall══] ·  ·   cell lat=1: lateral 0.5..1.5 at depth 2
    //     |              ╲  ╱        left  angle = 0.5/2 = 0.25
    //   1 ·  ·  ·  ·  ·  ╲╱         right angle = 1.5/2 = 0.75
    //     |                *         angular interval: [0.25, 0.75]
    //     0   0.5   1   1.5 → lateral
    //
    // Side wall at boundary xB, strip sideDepth→D — face runs along fixed lateral xB:
    //
    //   depth
    //     ↑
    //   2 ·  F  ·  far end at (xB=0.5, depth=2): angle = 0.5/2 = 0.25
    //     |  |╲
    //   1 ·  N  ╲  near end at (xB=0.5, depth=1): angle = 0.5/1 = 0.5
    //     |  face ╲                angular interval: [0.25, 0.5]
    //   0 ·  *─────╲──→ lateral   (all rays that could reach any point on the face)
    //        0  0.5  1
    //
    // Because both feature types map to the same angular number line, a single covered entry
    // from a close wall blocks both front faces and side faces behind it with no special cases.
    //
    // -----------------------------------------------------------------------------------------
    // Angular occlusion buffer (equivalent to DOOM's solidsegs)
    // -----------------------------------------------------------------------------------------
    // `covered` is a sorted, non-overlapping list of angular intervals blocked by solid walls
    // found so far. subtractCoverage queries it; mergeInto extends it.
    //
    // -----------------------------------------------------------------------------------------
    // Why the grid makes BSP unnecessary
    // -----------------------------------------------------------------------------------------
    // All geometry is axis-aligned and the viewer faces a cardinal direction, so every cell at
    // depth D is strictly further than every cell at depth D-1. The depth loop IS the front-to-
    // back ordering — no spatial tree is needed (contrast with DOOM, which uses a BSP tree to
    // guarantee front-to-back ordering for arbitrary wall angles).
    //
    // -----------------------------------------------------------------------------------------
    // Per-depth ordering (critical for correctness)
    // -----------------------------------------------------------------------------------------
    //   Step 1 — Lateral boundary test (side walls at strip D-1→D):
    //     Checked BEFORE adding D's coverage so a wall doesn't occlude its own adjacent side face.
    //     E.g. the right face of a dead-end wall at depth=1 must pass the coverage check at a
    //     moment when the dead-end wall itself has not yet contributed to `covered`.
    //   Step 2 — Open→wall transition test (front walls at depth D):
    //     Checked against coverage from depths < D. A front face is only emitted when the cell
    //     directly in front of it (depth D-1, same lat) is open — walls with no exposed face
    //     are skipped, but they still contribute to coverage in step 3.
    //   Step 3 — Coverage update:
    //     Every solid wall cell at depth D, rendered or not, registers its angular interval in
    //     `covered`. Must happen after steps 1 and 2 for the ordering reason above.
    //
    // -----------------------------------------------------------------------------------------
    // Complexity — O(D × W²)
    // -----------------------------------------------------------------------------------------
    //   D = viewDistance, W = fovWidth, n = size of `covered` (at most ⌈W/2⌉ intervals,
    //   since W slots can produce at most W/2 non-adjacent covered bands → n = O(W)).
    //
    //   Per depth level:
    //     Step 1: W boundaries × subtractCoverage O(n)  →  O(W·n)
    //     Step 2: W lats      × subtractCoverage O(n)  →  O(W·n)
    //     Step 3: W lats      × mergeInto        O(n)  →  O(W·n)
    //
    //   Total: O(D × W × n) = O(D × W²).
    //   For typical blobber values (D=4, W=5): ≈100 iterations per frame — effectively O(1).
    private fun updateWalls(viewW: Float, viewH: Float): List<DrawCommand> {
        log("updateWalls")
        val right = viewer.facing.turnedRight()
        val latMax = fovWidth / 2
        val newFrontWallCells = mutableMapOf<Pair<Int, Int>, String>()
        val newSideWallCells = mutableMapOf<Pair<Int, Int>, String>()

        // frustumAngleHalf is the maximum visible angle: a ray to the far corner of the outermost
        // slot (lat = ±fovHalf at depth = viewDistance) has angle ±fovHalf/viewDistance.
        val frustumAngleHalf = fovHalf / viewDistance
        val covered = mutableListOf<Pair<Float, Float>>()

        // Converts an angular position to a DrawScope x coordinate.
        // screen_x_scene = angle × viewW × viewDistance / fovWidth; +viewW/2 offsets to DrawScope.
        val angleToX = viewW * viewDistance.toFloat() / fovWidth.toFloat()
        fun Float.toDsX() = this * angleToX + viewW / 2f

        val drawCommands = mutableListOf<DrawCommand>()

        // ---------------------------------------------------------------------------------
        // Floor and ceiling: emitted as per-(lat, depth) cell slots so each cell can
        // eventually carry its own texture.
        //
        // yWallBottom(D) = viewH/2 + wallHeightScale × viewH / (2 × D) — screen-y of the
        // base of a depth-D wall.  Open cell (lat, D) occupies floor strip:
        //   y ∈ [yWallBottom(D+1), yWallBottom(D)],  x ∈ angular sub-interval of that slot.
        //
        // Emission order:
        //   • Near band (depth 0→1): viewer's own floor, always full frustum width.
        //   • Per slot (lat, D) after step 3 of depth D: only open cells, angular-occlusion culled.
        //   Beyond viewDistance nothing is drawn — the viewport background (black) provides the cutoff.
        // ---------------------------------------------------------------------------------
        fun wallBottomY(d: Int): Float {
            require(d > 0) { "wallBottomY: d must be > 0, got $d" }
            return viewH / 2f + wallHeightScale * viewH / (2f * d)
        }

        fun emitFloorCeiling(
            yTop: Float,
            yBottom: Float,
            subIntervals: List<Pair<Float, Float>>,
            floorColor: Color,
            ceilColor: Color,
        ) {
            for ((αA, αB) in subIntervals) {
                drawCommands.add(DrawCommand.FloorCeilingBand(
                    yFloorClipTop = yTop,
                    yFloorClipBottom = yBottom,
                    xClipLeft = αA.toDsX(),
                    xClipRight = αB.toDsX(),
                    floorColor = floorColor,
                    ceilColor = ceilColor,
                ))
            }
        }

        // Near band: floor/ceiling of viewer's own cell — always full frustum width.
        emitFloorCeiling(
            yTop = wallBottomY(1), yBottom = viewH,
            subIntervals = listOf(-frustumAngleHalf to frustumAngleHalf),
            floorColor = floorBandColor(0), ceilColor = ceilingBandColor(0),
        )

        for (D in 1..viewDistance) {
            val sideDepth = D - 1  // near depth of this strip; far depth is D

            // ---------------------------------------------------------------------------------
            // Step 1 — Lateral boundary test: side walls at depth strip sideDepth → D
            //
            // A side wall exists at boundary k when exactly one of the two flanking cells is a
            // wall. k = latBoundaryTimes2: the seam between leftLat=(k-1)/2 and rightLat=(k+1)/2
            // sits at frustum lateral position xB = k/2. For fovWidth=4 the boundaries are
            // k ∈ {-3,-1,+1,+3}, i.e. xB = ±0.5, ±1.5.
            // ---------------------------------------------------------------------------------
            for (k in -(2 * latMax - 1)..(2 * latMax - 1) step 2) {
                val leftLat = (k - 1) / 2
                val rightLat = (k + 1) / 2
                val xB = k / 2f

                val leftCellX = viewer.cellX + sideDepth * viewer.facing.dx + leftLat * right.dx
                val leftCellY = viewer.cellY + sideDepth * viewer.facing.dy + leftLat * right.dy
                val rightCellX = viewer.cellX + sideDepth * viewer.facing.dx + rightLat * right.dx
                val rightCellY = viewer.cellY + sideDepth * viewer.facing.dy + rightLat * right.dy
                val leftIsWall = tileMapManager.tileMap.cellTypeAt(leftCellX, leftCellY) == CellType.WALL
                val rightIsWall = tileMapManager.tileMap.cellTypeAt(rightCellX, rightCellY) == CellType.WALL
                // Both cells same type → interior seam (wall/wall) or open gap (open/open): no face.
                if (leftIsWall == rightIsWall) continue

                // Inward-neighbour occlusion: if the wall cell's neighbour one step closer to
                // lat=0 (same depth) is also a wall, this face is interior to a wall block and
                // cannot be seen from the party. lat - sign(lat) steps one slot toward centre.
                val wallLat = if (leftIsWall) leftLat else rightLat
                if (wallLat != 0) {
                    val inwardLat = wallLat - if (wallLat > 0) 1 else -1
                    val inwardCellX = viewer.cellX + sideDepth * viewer.facing.dx + inwardLat * right.dx
                    val inwardCellY = viewer.cellY + sideDepth * viewer.facing.dy + inwardLat * right.dy
                    if (tileMapManager.tileMap.cellTypeAt(inwardCellX, inwardCellY) == CellType.WALL) continue
                }

                // Geometry pre-check: skip zero-width trapezoids and strips entirely off-screen.
                // xNear is the screen-x of the near edge (party side); xFar is the far edge.
                // At sideDepth=0 the formula diverges (division by zero), so clamp to screen edge.
                val xNear = if (sideDepth == 0) (if (xB > 0f) viewW / 2f else -viewW / 2f)
                            else xB * viewW * viewDistance / (fovWidth * sideDepth)
                val xFar = xB * viewW * viewDistance / (fovWidth * D)
                if (xNear == xFar) continue
                // xFar is the edge closer to screen centre; if it's already outside ±viewW/2 the
                // whole strip is off-screen (the near edge is even further out).
                if (xFar > viewW / 2f || xFar < -viewW / 2f) continue

                // Angular occlusion check: the strip spans angles from xB/D (far end, smaller
                // angle) to xB/sideDepth (near end, larger angle). For negative xB the signs
                // flip but minOf/maxOf handles both sides symmetrically.
                // For sideDepth=0 the near end extends to the screen edge (÷0 avoided by
                // clamping nearAngle to ±frustumAngleHalf directly).
                val nearAngle = if (sideDepth == 0) (if (xB > 0f) frustumAngleHalf else -frustumAngleHalf)
                                else xB / sideDepth
                val sideAngleMin = minOf(xB / D, nearAngle).coerceAtLeast(-frustumAngleHalf)
                val sideAngleMax = maxOf(xB / D, nearAngle).coerceAtMost(frustumAngleHalf)

                if (sideAngleMin >= sideAngleMax) continue
                val subIntervals = subtractCoverage(sideAngleMin to sideAngleMax, covered)
                if (subIntervals.isEmpty()) continue

                // Trapezoid geometry (same projection as the old syncSideWallActors).
                val yFarHalf = viewH * wallHeightScale / (2f * D)
                val yNearHalf = if (sideDepth == 0) (viewW / 2f) * yFarHalf / kotlin.math.abs(xFar)
                               else viewH * wallHeightScale / (2f * sideDepth)
                val clampedXNear = xNear.coerceIn(-viewW / 2f, viewW / 2f)
                val clampedYNearHalf = if (clampedXNear == xNear || xFar == xNear) yNearHalf
                    else yNearHalf + (clampedXNear - xNear) / (xFar - xNear) * (yFarHalf - yNearHalf)

                // Convert scene-space trapezoid to DrawScope space (origin = viewport top-left).
                val xNear_ds = clampedXNear + viewW / 2f
                val xFar_ds = xFar + viewW / 2f
                val yNearTop_ds = viewH / 2f - clampedYNearHalf
                val yNearBot_ds = viewH / 2f + clampedYNearHalf
                val yFarTop_ds = viewH / 2f - yFarHalf
                val yFarBot_ds = viewH / 2f + yFarHalf
                val color = sideWallTexturedColor(sideDepth)

                log("updateWalls", "add side wall lat=$wallLat, depth=$sideDepth")
                val (wCellX, wCellY) = if (leftIsWall) leftCellX to leftCellY else rightCellX to rightCellY
                newSideWallCells[wCellX to wCellY] = "$k,$sideDepth"

                // One strip per visible sub-interval; debug label on the first only.
                subIntervals.forEachIndexed { idx, (subA, subB) ->
                    drawCommands.add(DrawCommand.SideStrip(
                        xNear = xNear_ds, xFar = xFar_ds,
                        yNearTop = yNearTop_ds, yNearBot = yNearBot_ds,
                        yFarTop = yFarTop_ds, yFarBot = yFarBot_ds,
                        xClipLeft = subA.toDsX(),
                        xClipRight = subB.toDsX(),
                        color = color,
                        debugLabel = if (idx == 0) textMeasurer?.measure(
                            "$k,$sideDepth",
                            style = TextStyle(fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.Cyan),
                        ) else null,
                    ))
                }
                // Side walls are solid surfaces — mark their angular range covered so
                // deeper features in the same angular interval are correctly occluded.
                // sideDepth=0 walls are included: step 3 only processes depth-D cells.
                mergeInto(covered, sideAngleMin to sideAngleMax)
            }

            // ---------------------------------------------------------------------------------
            // Step 2 — Open→wall transition test: front walls at depth D
            //
            // A front face is only visible when the party can see the face directly — i.e. the
            // cell at (lat, D) is a wall but the cell at (lat, D-1) is open. A wall preceded by
            // another wall has no exposed front face. The angular coverage check then culls faces
            // hidden behind closer walls across different lateral columns.
            // ---------------------------------------------------------------------------------
            for (lat in -latMax..latMax) {
                val latLeft = lat - 0.5f
                val latRight = lat + 0.5f

                // Frustum clip: skip slots entirely outside the outer frustum boundary (e.g.
                // lat=±3 with fovWidth=4 where fovHalf=2) or not yet visible at this depth
                // (the frustum narrows toward the apex — at depth D only lats within
                // ±D*fovHalf/viewDistance are in view).
                if (maxOf(latLeft, -fovHalf) >= minOf(latRight, fovHalf)) continue
                val visibleLatHalf = D * fovHalf / viewDistance
                if (maxOf(latLeft, -visibleLatHalf) >= minOf(latRight, visibleLatHalf)) continue

                val cellX = viewer.cellX + D * viewer.facing.dx + lat * right.dx
                val cellY = viewer.cellY + D * viewer.facing.dy + lat * right.dy
                if (tileMapManager.tileMap.cellTypeAt(cellX, cellY) != CellType.WALL) continue

                // Angular occlusion check: use the sub-intervals directly for clipped rendering.
                val angleLeft = maxOf(latLeft / D, -frustumAngleHalf)
                val angleRight = minOf(latRight / D, frustumAngleHalf)
                if (angleLeft >= angleRight) continue
                val subIntervals = subtractCoverage(angleLeft to angleRight, covered)
                if (subIntervals.isEmpty()) continue

                // Open→wall transition: only emit the face if the cell at depth D-1 is open.
                // Walls with no exposed face are skipped here but still add to coverage in step 3.
                val prevCellX = viewer.cellX + (D - 1) * viewer.facing.dx + lat * right.dx
                val prevCellY = viewer.cellY + (D - 1) * viewer.facing.dy + lat * right.dy
                if (tileMapManager.tileMap.cellTypeAt(prevCellX, prevCellY) != CellType.WALL) {
                    log("updateWalls", "add front wall $lat, $D")
                    newFrontWallCells[cellX to cellY] = "$lat,$D"

                    val slotHeight = viewH * wallHeightScale / D
                    val yTop = viewH / 2f - slotHeight / 2f
                    val yBottom = viewH / 2f + slotHeight / 2f
                    val color = frontWallTexturedColor(D)

                    val xWallLeft_ds = angleLeft.toDsX()
                    val xWallRight_ds = angleRight.toDsX()
                    subIntervals.forEachIndexed { idx, (subA, subB) ->
                        drawCommands.add(DrawCommand.FrontStrip(
                            xLeft = subA.toDsX(),
                            xRight = subB.toDsX(),
                            yTop = yTop,
                            yBottom = yBottom,
                            color = color,
                            xWallLeft = xWallLeft_ds,
                            xWallRight = xWallRight_ds,
                            debugLabel = if (idx == 0) textMeasurer?.measure(
                                "$lat,$D",
                                style = TextStyle(fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.Yellow),
                            ) else null,
                        ))
                    }
                }
            }

            // ---------------------------------------------------------------------------------
            // Step 3 — Coverage update: register solid wall cells at depth D
            //
            // Every solid cell at depth D — rendered or not — adds its angular interval to
            // `covered`. This must happen AFTER steps 1 and 2 so that a wall doesn't shadow
            // its own side face (step 1) or its own front face (step 2).
            // ---------------------------------------------------------------------------------
            for (lat in -latMax..latMax) {
                val angleLeft = maxOf((lat - 0.5f) / D, -frustumAngleHalf)
                val angleRight = minOf((lat + 0.5f) / D, frustumAngleHalf)
                if (angleLeft >= angleRight) continue
                val cellX = viewer.cellX + D * viewer.facing.dx + lat * right.dx
                val cellY = viewer.cellY + D * viewer.facing.dy + lat * right.dy
                if (tileMapManager.tileMap.cellTypeAt(cellX, cellY) == CellType.WALL) {
                    mergeInto(covered, angleLeft to angleRight)
                }
            }

            // Floor/ceiling per open cell at depth D.
            // Emitted after step 3 so coverage includes every wall at depths 1..D.
            // Only open cells emit (wall cells have their front face drawn instead).
            for (lat in -latMax..latMax) {
                val latLeft = lat - 0.5f
                val latRight = lat + 0.5f
                if (maxOf(latLeft, -fovHalf) >= minOf(latRight, fovHalf)) continue
                val visibleLatHalf = D * fovHalf / viewDistance
                if (maxOf(latLeft, -visibleLatHalf) >= minOf(latRight, visibleLatHalf)) continue

                val fCellX = viewer.cellX + D * viewer.facing.dx + lat * right.dx
                val fCellY = viewer.cellY + D * viewer.facing.dy + lat * right.dy
                if (tileMapManager.tileMap.cellTypeAt(fCellX, fCellY) == CellType.WALL) continue

                val angleLeft = maxOf(latLeft / D, -frustumAngleHalf)
                val angleRight = minOf(latRight / D, frustumAngleHalf)
                if (angleLeft >= angleRight) continue
                val subIntervals = subtractCoverage(angleLeft to angleRight, covered)
                if (subIntervals.isEmpty()) continue

                emitFloorCeiling(
                    yTop = wallBottomY(D + 1), yBottom = wallBottomY(D),
                    subIntervals = subIntervals,
                    floorColor = floorBandColor(D), ceilColor = ceilingBandColor(D),
                )
            }
        }

        _frontWallCells.value = newFrontWallCells
        _sideWallCells.value = newSideWallCells

        return drawCommands
    }

    // Warm stone colours for front walls: nearer cells are brighter (torchlight falloff).
    private fun frontWallTexturedColor(depth: Int): Color {
        val t = 1f - (depth - 1f) / (viewDistance - 1f).coerceAtLeast(1f)
        return Color(red = 0.18f + 0.36f * t, green = 0.10f + 0.27f * t, blue = 0.05f + 0.17f * t)
    }

    // Side walls are darker (face away from the imagined torch carried by the party).
    private fun sideWallTexturedColor(depth: Int): Color {
        val t = 1f - depth.toFloat() / viewDistance.toFloat()
        return Color(
            red = (0.18f + 0.36f * t) * 0.65f,
            green = (0.10f + 0.27f * t) * 0.65f,
            blue = (0.05f + 0.17f * t) * 0.65f,
        )
    }

    // Floor: warm dark stone, slightly brighter closer to the viewer.
    private fun floorBandColor(depth: Int): Color {
        val t = 1f - depth.toFloat() / (viewDistance + 1f)
        return Color(red = 0.08f + 0.10f * t, green = 0.05f + 0.07f * t, blue = 0.02f + 0.03f * t)
    }

    // Ceiling: cooler and darker than the floor (less direct torchlight reaches the vault).
    private fun ceilingBandColor(depth: Int): Color {
        val t = 1f - depth.toFloat() / (viewDistance + 1f)
        return Color(red = 0.05f + 0.06f * t, green = 0.03f + 0.04f * t, blue = 0.01f + 0.03f * t)
    }

    // Angular interval subtraction — the read side of the occlusion buffer.
    //
    // Returns the sub-intervals of [interval] not yet covered by any entry in [covered].
    // An empty result means the interval is fully occluded. Non-empty sub-intervals are used
    // directly to clip wall strip geometry to only the visible angular range.
    private fun subtractCoverage(
        interval: Pair<Float, Float>,
        covered: List<Pair<Float, Float>>,
    ): List<Pair<Float, Float>> {
        var remaining = listOf(interval)
        for ((covL, covR) in covered) {
            remaining = remaining.flatMap { (a, b) ->
                buildList {
                    if (a < covL) add(a to minOf(b, covL))
                    if (b > covR) add(maxOf(a, covR) to b)
                }
            }
            if (remaining.isEmpty()) break
        }
        return remaining
    }

    // Angular interval union — the write side of the occlusion buffer.
    //
    // Inserts [newInterval] into [covered], merging any overlapping or touching entries so the
    // list stays sorted and non-overlapping. This is an O(n) single-pass merge: walk the existing
    // list, emit intervals that end before the new one starts unchanged, absorb intervals that
    // overlap (expanding the new interval to their union), then emit intervals that start after
    // the new one unchanged.
    private fun mergeInto(covered: MutableList<Pair<Float, Float>>, newInterval: Pair<Float, Float>) {
        var (l, r) = newInterval
        val merged = mutableListOf<Pair<Float, Float>>()
        var inserted = false
        for ((covL, covR) in covered) {
            when {
                covR < l -> merged.add(covL to covR)
                covL > r -> {
                    if (!inserted) { merged.add(l to r); inserted = true }
                    merged.add(covL to covR)
                }
                else -> { l = minOf(l, covL); r = maxOf(r, covR) }
            }
        }
        if (!inserted) merged.add(l to r)
        covered.clear()
        covered.addAll(merged)
    }
}
