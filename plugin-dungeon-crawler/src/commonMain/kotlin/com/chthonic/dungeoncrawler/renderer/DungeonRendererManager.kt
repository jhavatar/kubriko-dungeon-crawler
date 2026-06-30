package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import com.chthonic.dungeoncrawler.tilemap.CellType
import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.GridPosition
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.manager.ActorManager
import com.pandulapeter.kubriko.manager.Manager
import com.pandulapeter.kubriko.manager.ViewportManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.time.measureTime

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
    // Controls rendering style and carries mode-specific config (colours, atlas, etc.).
    renderMode: RenderMode = RenderMode.Solid(),
    // When non-null, called once per wall strip to produce a debug label overlay.
    // Receives the label text and true for a side wall / false for a front wall.
    private val debugLabelProvider: ((text: String, isSideWall: Boolean) -> TextLayoutResult?)? = null,
    isLoggingEnabled: Boolean = false,
    instanceNameForLogging: String? = null,
) : Manager(
    isLoggingEnabled = isLoggingEnabled,
    instanceNameForLogging = instanceNameForLogging,
    classNameForLogging = DungeonRendererManager::class.simpleName,
) {
    // exp(-TORCH_K * dist) — torch brightness falls off exponentially with Euclidean distance.
    // TORCH_K controls the falloff rate; MIN_BRIGHTNESS prevents surfaces going fully black.
    private fun torchBrightness(dist: Float) = exp(-TORCH_K * (dist - 1f)).coerceIn(MIN_BRIGHTNESS, 1f)

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
        set(value) {
            field = value; lastRenderMode = null
        }

    private val fovHalf = fovWidth / 2f
    private val frustumAngleHalf = fovHalf / viewDistance

    // Reusable frame buffers — cleared and refilled each update, never reallocated.
    private val drawCommandsBuffer = mutableListOf<DrawCommand>()
    private val newFrontWallCellsBuffer = mutableMapOf<Pair<Int, Int>, String>()
    private val newSideWallCellsBuffer = mutableMapOf<Pair<Int, Int>, String>()

    // Angular occlusion buffer tracking which frustum sub-intervals are covered by solid walls.
    private val occlusionBuffer = AngularOcclusionBuffer(-frustumAngleHalf, frustumAngleHalf)

    // Local copy of the logging flag: Manager exposes no protected accessor, so we store it
    // here to guard hot-path log calls and prevent string interpolation when logging is off.
    private val debugLogging = isLoggingEnabled

    // Map from (mapCellX, mapCellY) → debug label for each visible front wall cell.
    // Coordinates are resolved in map space at compute time so the minimap can use them directly.
    private val _frontWallCells = MutableStateFlow<Map<Pair<Int, Int>, String>>(emptyMap())
    val frontWallCells: StateFlow<Map<Pair<Int, Int>, String>> = _frontWallCells.asStateFlow()

    // Map from (mapCellX, mapCellY) → debug label for each visible side wall cell.
    private val _sideWallCells = MutableStateFlow<Map<Pair<Int, Int>, String>>(emptyMap())
    val sideWallCells: StateFlow<Map<Pair<Int, Int>, String>> = _sideWallCells.asStateFlow()

    override fun onUpdate(deltaTimeInMilliseconds: Int) {
        val viewW = (viewportManager.bottomRight.value.x - viewportManager.topLeft.value.x).raw
        val viewH = (viewportManager.bottomRight.value.y - viewportManager.topLeft.value.y).raw
        if (viewW == 0f || viewH == 0f) return

        val viewChanged = viewW != lastViewW || viewH != lastViewH
        val positionChanged =
            viewer.cellX != lastCellX || viewer.cellY != lastCellY || viewer.facing != lastFacing
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
        if (debugLogging) log("onUpdate")
        val buildDrawCommandsElapsed = measureTime {
            checkNotNull(dungeonViewActor).update(buildDrawCommands(viewW, viewH))
        }
        if (debugLogging) log(
            "onUpdate",
            "render ${buildDrawCommandsElapsed.inWholeMicroseconds}µs, ${drawCommandsBuffer.size} cmds"
        )
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
    private fun buildDrawCommands(viewW: Float, viewH: Float): List<DrawCommand> {
        if (debugLogging) log("buildDrawCommands")
        val tileMap = tileMapManager.tileMap
        val right = viewer.facing.turnedRight()
        val latMax = fovHalf.toInt()
        newFrontWallCellsBuffer.clear()
        newSideWallCellsBuffer.clear()
        drawCommandsBuffer.clear()
        val colorTheme = when (val m = renderMode) {
            is RenderMode.Solid -> m.colorTheme
            is RenderMode.Textured -> m.colorTheme
            is RenderMode.Wireframe -> DungeonColorTheme()  // colors unused in wireframe draw
        }
        val atlas = (renderMode as? RenderMode.Textured)?.atlas
        val frontWallTile = atlas?.frontWallTile ?: 0
        val sideWallTile  = atlas?.sideWallTile  ?: 0
        val floorTile     = atlas?.floorTile      ?: 0
        val ceilTile      = atlas?.ceilTile       ?: 0

        occlusionBuffer.clear()

        // Converts an angular position to a DrawScope x coordinate.
        // screen_x_scene = angle × viewW × viewDistance / fovWidth; +viewW/2 offsets to DrawScope.
        val angleToX = viewW * viewDistance.toFloat() / fovWidth.toFloat()
        fun Float.toDsX() = this * angleToX + viewW / 2f

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

        // Near band: floor/ceiling for every open cell at depth 0→1.
        //
        // The near band spans from yTop=wallBottomY(1) to yBottom=viewH. wallBottomY(d)=viewH
        // when d=wallHeightScale, so the "near" y-edge corresponds to depth d=wallHeightScale,
        // not depth 0. xNear is the cell-boundary projection at that finite depth — for lat=0
        // this gives [0, viewW]; for lat=±1 the near corners are off-screen but well-defined,
        // giving the correct converging trapezoid shape (e.g. lat=+1: xNearLeft=viewW so the
        // left boundary runs from (0.9·W, 0.9·H) → (viewW, viewH), not back toward x=0).
        //
        // lat=0 uses the full frustum interval [−frustumAngleHalf, +frustumAngleHalf] so
        // that xClipLeft=0 and xClipRight=viewW. drawFloorCeilingTextured expands the clip
        // only toward xFar (not xNear), so the clip stays [0, viewW] and the full lat=0
        // trapezoid — including the diagonal near-edge corners — is drawn.
        //
        // Lateral cells (lat=±1, ±2…) use the frustum-clamped cell angular range as their
        // sub-interval, giving a narrow clip (e.g. [0.9·W, W] for lat=+1). canvas.clipPath
        // constrains drawing to the corner triangle that the lat=0 trapezoid cannot reach.
        //
        // Emission order: lat=0 first, then lateral cells outward. Lateral bands draw on
        // top of lat=0 in the corner triangle area, replacing it with the cell's tile.
        for (latAbs in 0..latMax) {
            val lats = if (latAbs == 0) listOf(0) else listOf(-latAbs, latAbs)
            for (lat in lats) {
                val latLeft  = lat - 0.5f
                val latRight = lat + 0.5f
                val angleLeft  = if (lat == 0) -frustumAngleHalf else maxOf(latLeft,  -frustumAngleHalf)
                val angleRight = if (lat == 0)  frustumAngleHalf else minOf(latRight,  frustumAngleHalf)
                if (angleLeft >= angleRight) continue

                val nCellX = viewer.cellX + lat * right.dx
                val nCellY = viewer.cellY + lat * right.dy
                if (tileMap.cellTypeAt(nCellX, nCellY) == CellType.WALL) continue

                val nearSubIntervals = if (renderMode is RenderMode.Wireframe)
                    occlusionBuffer.subtract(Interval(angleLeft, angleRight))
                else listOf(Interval(angleLeft, angleRight))
                if (nearSubIntervals.isEmpty()) continue
                emitFloorCeiling(
                    angleToX = angleToX,
                    viewW = viewW,
                    yTop = wallBottomY(1),
                    yBottom = viewH,
                    xNearLeft  = latLeft  / wallHeightScale * angleToX + viewW / 2f,
                    xNearRight = latRight / wallHeightScale * angleToX + viewW / 2f,
                    xFarLeft   = latLeft  * angleToX + viewW / 2f,
                    xFarRight  = latRight * angleToX + viewW / 2f,
                    subIntervals = nearSubIntervals,
                    floorColor = (renderMode as? RenderMode.Wireframe)?.floorColor ?: colorTheme.floorColor(torchBrightness(0.5f)),
                    ceilColor = (renderMode as? RenderMode.Wireframe)?.ceilColor ?: colorTheme.ceilColor(torchBrightness(0.5f)),
                    floorTileIndex = floorTile,
                    ceilTileIndex = ceilTile,
                    vNearFraction = 1f - wallHeightScale,
                    floorNearBrightness = 1f,
                    floorFarBrightness = torchBrightness(hypot(1f, lat.toFloat())),
                    ceilNearBrightness = 1f,
                    ceilFarBrightness = torchBrightness(hypot(1f, lat.toFloat())),
                )
            }
        }

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
                val leftIsWall = tileMap.cellTypeAt(leftCellX, leftCellY) == CellType.WALL
                val rightIsWall = tileMap.cellTypeAt(rightCellX, rightCellY) == CellType.WALL
                // Both cells same type → interior seam (wall/wall) or open gap (open/open): no face.
                if (leftIsWall == rightIsWall) continue

                // Inward-neighbour occlusion: if the wall cell's neighbour one step closer to
                // lat=0 (same depth) is also a wall, this face is interior to a wall block and
                // cannot be seen from the party. lat - sign(lat) steps one slot toward centre.
                val wallLat = if (leftIsWall) leftLat else rightLat
                if (wallLat != 0) {
                    val inwardLat = wallLat - if (wallLat > 0) 1 else -1
                    val inwardCellX =
                        viewer.cellX + sideDepth * viewer.facing.dx + inwardLat * right.dx
                    val inwardCellY =
                        viewer.cellY + sideDepth * viewer.facing.dy + inwardLat * right.dy
                    if (tileMap.cellTypeAt(inwardCellX, inwardCellY) == CellType.WALL) continue
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
                val nearAngle =
                    if (sideDepth == 0) (if (xB > 0f) frustumAngleHalf else -frustumAngleHalf)
                    else xB / sideDepth
                val sideAngleMin = minOf(xB / D, nearAngle).coerceAtLeast(-frustumAngleHalf)
                val sideAngleMax = maxOf(xB / D, nearAngle).coerceAtMost(frustumAngleHalf)

                if (sideAngleMin >= sideAngleMax) continue
                val subIntervals = occlusionBuffer.subtract(Interval(sideAngleMin, sideAngleMax))
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
                val color = colorTheme.sideWallColor(torchBrightness(hypot(sideDepth.toFloat() + 0.5f, xB)))

                // Fraction of the tile U range hidden behind the screen edge at the near end.
                // At sideDepth=0 the wall extends from the player (d=0, off-screen) to d=D, so
                // only the d=[dEntry, D] slice is visible. For deeper walls dEntry ≤ sideDepth
                // meaning the full near face is on screen and the fraction is 0.
                val dEntry = kotlin.math.abs(xB) * viewDistance.toFloat() / fovHalf
                val tileUNearFraction = if (dEntry <= sideDepth) 0f else
                    ((dEntry - sideDepth) / (D - sideDepth).toFloat()).coerceIn(0f, 1f)

                if (debugLogging) log("buildDrawCommands", "add side wall $wallLat, $sideDepth")
                val (wCellX, wCellY) = if (leftIsWall) leftCellX to leftCellY else rightCellX to rightCellY
                newSideWallCellsBuffer[wCellX to wCellY] = "$k,$sideDepth"

                // One strip per visible sub-interval; debug label on the first only.
                subIntervals.forEachIndexed { idx, (lo, hi) ->
                    drawCommandsBuffer.add(
                        DrawCommand.SideStrip(
                            xNear = xNear_ds, xFar = xFar_ds,
                            yNearTop = yNearTop_ds, yNearBot = yNearBot_ds,
                            yFarTop = yFarTop_ds, yFarBot = yFarBot_ds,
                            xClipLeft = lo.toDsX(),
                            xClipRight = hi.toDsX(),
                            color = color,
                            tileIndex = sideWallTile,
                            brightness = torchBrightness(hypot(sideDepth.toFloat() + 0.5f, xB)) * if (renderMode is RenderMode.Wireframe) 1f else colorTheme.sideWallShadow,
                            tileUNearFraction = tileUNearFraction,
                            debugLabel = if (idx == 0) debugLabelProvider?.invoke(
                                "$k,$sideDepth",
                                true
                            ) else null,
                        )
                    )
                }
                // Side walls are solid surfaces — mark their angular range covered so
                // deeper features in the same angular interval are correctly occluded.
                // sideDepth=0 walls are included: step 3 only processes depth-D cells.
                occlusionBuffer.merge(Interval(sideAngleMin, sideAngleMax))
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

                if (!isInFrustum(latLeft, latRight, D)) continue

                val cellX = viewer.cellX + D * viewer.facing.dx + lat * right.dx
                val cellY = viewer.cellY + D * viewer.facing.dy + lat * right.dy
                if (tileMap.cellTypeAt(cellX, cellY) != CellType.WALL) continue

                // Angular occlusion check: use the sub-intervals directly for clipped rendering.
                val angleLeft = maxOf(latLeft / D, -frustumAngleHalf)
                val angleRight = minOf(latRight / D, frustumAngleHalf)
                if (angleLeft >= angleRight) continue
                val subIntervals = occlusionBuffer.subtract(Interval(angleLeft, angleRight))
                if (subIntervals.isEmpty()) continue

                // Open→wall transition: only emit the face if the cell at depth D-1 is open.
                // Walls with no exposed face are skipped here but still add to coverage in step 3.
                val prevCellX = viewer.cellX + (D - 1) * viewer.facing.dx + lat * right.dx
                val prevCellY = viewer.cellY + (D - 1) * viewer.facing.dy + lat * right.dy
                if (tileMap.cellTypeAt(prevCellX, prevCellY) != CellType.WALL) {
                    if (debugLogging) log("buildDrawCommands", "add front wall $lat, $D")
                    newFrontWallCellsBuffer[cellX to cellY] = "$lat,$D"

                    val slotHeight = viewH * wallHeightScale / D
                    val yTop = viewH / 2f - slotHeight / 2f
                    val yBottom = viewH / 2f + slotHeight / 2f
                    val color = colorTheme.frontWallColor(torchBrightness(hypot(D.toFloat(), lat.toFloat())))

                    // Use the full (unclipped) cell angular extent so that UV sampling in
                    // drawFrontStrip spans the correct slice of the tile even when the frustum
                    // clips one edge of the cell (e.g. lat=±latMax at D < viewDistance).
                    val xWallLeft_ds  = (latLeft  / D).toDsX()
                    val xWallRight_ds = (latRight / D).toDsX()
                    val frontBrightness = torchBrightness(hypot(D.toFloat(), lat.toFloat()))
                    subIntervals.forEachIndexed { idx, (lo, hi) ->
                        drawCommandsBuffer.add(
                            DrawCommand.FrontStrip(
                                xLeft = lo.toDsX(),
                                xRight = hi.toDsX(),
                                tileIndex = frontWallTile,
                                brightness = frontBrightness,
                                yTop = yTop,
                                yBottom = yBottom,
                                color = color,
                                xWallLeft = xWallLeft_ds,
                                xWallRight = xWallRight_ds,
                                debugLabel = if (idx == 0) debugLabelProvider?.invoke(
                                    "$lat,$D",
                                    false
                                ) else null,
                            )
                        )
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
                if (tileMap.cellTypeAt(cellX, cellY) == CellType.WALL) {
                    occlusionBuffer.merge(Interval(angleLeft, angleRight))
                }
            }

            // Floor/ceiling per open cell at depth D.
            // Only open cells emit (wall cells have their front face drawn instead).
            // D == viewDistance is skipped: that iteration only draws the front wall at the
            // far clip boundary; the floor of that cell (depth D→D+1) is beyond view range.
            // No occlusion-buffer check here: the buffer is depth-agnostic, so a side wall at
            // sideDepth 0..D would wrongly block floor/ceiling beyond its actual depth range
            // (e.g. a sideDepth=0 wall covers depth 0..1 only, but the buffer would also block
            // the floor at depth 1..2 in the same angular range). Instead, emit every visible
            // open cell's full frustum-clamped interval and rely on Pass 2 walls to occlude any
            // over-draw — walls are opaque and always drawn on top of Pass 1 floor/ceiling.
            if (D < viewDistance) for (lat in -latMax..latMax) {
                val latLeft = lat - 0.5f
                val latRight = lat + 0.5f
                // Floor band spans depth D→D+1: check visibility against the far edge (D+1),
                // where the frustum is wider. Using D alone misses cells that enter the frustum
                // partway through the band (e.g. lat=2 at D=2 is visible from depth 2.4 onward).
                if (!isInFrustum(latLeft, latRight, D + 1)) continue

                val fCellX = viewer.cellX + D * viewer.facing.dx + lat * right.dx
                val fCellY = viewer.cellY + D * viewer.facing.dy + lat * right.dy
                if (tileMap.cellTypeAt(fCellX, fCellY) == CellType.WALL) continue

                // angleLeft/angleRight use the extreme angles each boundary reaches in [D, D+1].
                // For positive lat the leftmost angle is at the far edge (D+1); for negative lat at D.
                // Symmetrically, for negative lat the rightmost angle is at the far edge (D+1); for positive at D.
                val angleLeft  = maxOf(minOf(latLeft  / D.toFloat(), latLeft  / (D + 1).toFloat()), -frustumAngleHalf)
                val angleRight = minOf(maxOf(latRight / D.toFloat(), latRight / (D + 1).toFloat()),  frustumAngleHalf)
                if (angleLeft >= angleRight) continue

                // Perspective quad corners: near edge at depth D (wider), far edge at D+1 (narrower).
                val fcXNearLeft  = latLeft  / D.toFloat() * angleToX + viewW / 2f
                val fcXNearRight = latRight / D.toFloat() * angleToX + viewW / 2f
                val fcXFarLeft   = latLeft  / (D + 1).toFloat() * angleToX + viewW / 2f
                val fcXFarRight  = latRight / (D + 1).toFloat() * angleToX + viewW / 2f
                val fcSubIntervals = if (renderMode is RenderMode.Wireframe)
                    occlusionBuffer.subtract(Interval(angleLeft, angleRight))
                else listOf(Interval(angleLeft, angleRight))
                if (fcSubIntervals.isEmpty()) continue
                emitFloorCeiling(
                    angleToX = angleToX,
                    viewW = viewW,
                    yTop = wallBottomY(D + 1),
                    yBottom = wallBottomY(D),
                    xNearLeft  = fcXNearLeft,
                    xNearRight = fcXNearRight,
                    xFarLeft   = fcXFarLeft,
                    xFarRight  = fcXFarRight,
                    subIntervals = fcSubIntervals,
                    floorColor = (renderMode as? RenderMode.Wireframe)?.floorColor ?: colorTheme.floorColor(torchBrightness(D.toFloat() + 0.5f)),
                    ceilColor = (renderMode as? RenderMode.Wireframe)?.ceilColor ?: colorTheme.ceilColor(torchBrightness(D.toFloat() + 0.5f)),
                    floorTileIndex = floorTile,
                    ceilTileIndex = ceilTile,
                    floorNearBrightness = torchBrightness(hypot(D.toFloat(), lat.toFloat())),
                    floorFarBrightness  = torchBrightness(hypot((D + 1).toFloat(), lat.toFloat())),
                    ceilNearBrightness  = torchBrightness(hypot(D.toFloat(), lat.toFloat())),
                    ceilFarBrightness   = torchBrightness(hypot((D + 1).toFloat(), lat.toFloat())),
                )
            }

            // Early exit: once the entire frustum is covered, no geometry at greater depths
            // can be visible — every subtract() would return empty. Break before the next D.
            if (occlusionBuffer.isFull) break
        }

        if (newFrontWallCellsBuffer != _frontWallCells.value) _frontWallCells.value =
            newFrontWallCellsBuffer.toMap()
        if (newSideWallCellsBuffer != _sideWallCells.value) _sideWallCells.value =
            newSideWallCellsBuffer.toMap()

        return drawCommandsBuffer
    }

    private companion object {
        // Exponential torch falloff: brightness = exp(-TORCH_K * (hypot(depth, lat) - 1))
        // Normalised at dist=1 so the nearest wall (D=1, lat=0) is always full brightness (1.0).
        // TORCH_K: smaller = wider torch radius / brighter dungeon; larger = tighter / darker.
        // MIN_BRIGHTNESS: ambient floor — raise for a dimly-lit dungeon, lower for starker drop.
        //
        // Reference brightness at TORCH_K=0.4, viewDistance=4:
        //   dist  1.0  (D=1, lat=0)  →  1.00
        //   dist  1.4  (D=1, lat=1)  →  0.85
        //   dist  2.0  (D=2, lat=0)  →  0.67
        //   dist  2.8  (D=2, lat=2)  →  0.49
        //   dist  4.0  (D=4, lat=0)  →  0.30
        const val TORCH_K = 0.4f
        const val MIN_BRIGHTNESS = 0.2f
    }

    // Returns true when the cell slot [latLeft, latRight] has any angular overlap with the visible
    // frustum at depth D. The frustum has a fixed outer half-width of fovHalf across the whole
    // depth range, but its visible portion narrows toward the apex — at depth D only slots within
    // ±D*fovHalf/viewDistance are reachable from the viewer. A slot that fails either check
    // (outside the outer boundary, or outside the depth-scaled inner frustum) can be skipped.
    private fun isInFrustum(latLeft: Float, latRight: Float, D: Int): Boolean {
        if (maxOf(latLeft, -fovHalf) >= minOf(latRight, fovHalf)) return false
        val visibleLatHalf = D * fovHalf / viewDistance
        return maxOf(latLeft, -visibleLatHalf) < minOf(latRight, visibleLatHalf)
    }

    // Emits floor and ceiling bands for each visible angular sub-interval of a cell slot.
    // angleToX and viewW are passed from buildDrawCommands so the method does not need the local
    // toDsX extension (which is only in scope inside buildDrawCommands).
    // xNear*/xFar* are the full perspective quad corners for perspective-correct texture mapping.
    private fun emitFloorCeiling(
        angleToX: Float,
        viewW: Float,
        yTop: Float,
        yBottom: Float,
        xNearLeft: Float,
        xNearRight: Float,
        xFarLeft: Float,
        xFarRight: Float,
        subIntervals: List<Interval>,
        floorColor: Color,
        ceilColor: Color,
        floorTileIndex: Int = 0,
        ceilTileIndex: Int = 0,
        vNearFraction: Float = 1f,
        floorNearBrightness: Float = 1f,
        floorFarBrightness: Float = 1f,
        ceilNearBrightness: Float = 1f,
        ceilFarBrightness: Float = 1f,
    ) {
        for ((lo, hi) in subIntervals) {
            drawCommandsBuffer.add(
                DrawCommand.FloorCeilingBand(
                    yFloorClipTop = yTop,
                    yFloorClipBottom = yBottom,
                    xClipLeft = lo * angleToX + viewW / 2f,
                    xClipRight = hi * angleToX + viewW / 2f,
                    floorColor = floorColor,
                    ceilColor = ceilColor,
                    xNearLeft = xNearLeft,
                    xNearRight = xNearRight,
                    xFarLeft = xFarLeft,
                    xFarRight = xFarRight,
                    floorTileIndex = floorTileIndex,
                    ceilTileIndex = ceilTileIndex,
                    vNearFraction = vNearFraction,
                    floorNearBrightness = floorNearBrightness,
                    floorFarBrightness = floorFarBrightness,
                    ceilNearBrightness = ceilNearBrightness,
                    ceilFarBrightness = ceilFarBrightness,
                )
            )
        }
    }

}
