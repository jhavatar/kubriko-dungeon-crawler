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
    // The entity whose position and facing drive the first-person view.
    private val viewer: GridPosition,
    // Number of lateral cell slots across the frustum at viewDistance. Odd values give a
    // centre slot; even values split symmetrically. Controls how wide the corridor view is.
    val fovWidth: Int = 5,
    // How many cells forward the frustum extends. Walls beyond this distance are not rendered.
    val viewDistance: Int = 4,
    // Fraction of the viewport height that a wall at depth=1 occupies (0 < scale ≤ 1).
    // Values below 1 leave floor and ceiling strips visible; 1.0 fills the full viewport.
    val wallHeightScale: Float = 0.8f,
    // Switches between solid-colour textured rendering and wireframe outline rendering.
    var renderMode: RenderMode = RenderMode.TEXTURED,
    // When non-null, enables debug labels drawn on each wall actor showing its (lat,depth) or
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
        updateWalls(viewW, viewH)
    }

    // Front-to-back angular occlusion traversal.
    //
    // Determines the complete set of visible front-face and side-face wall slots this frame,
    // then delegates actor lifecycle management to syncFrontWallActors / syncSideWallActors.
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
    private fun updateWalls(viewW: Float, viewH: Float) {
        log("updateWalls")
        val right = viewer.facing.turnedRight()
        val latMax = fovWidth / 2
        val newWalls = mutableSetOf<Pair<Int, Int>>()
        val newFrontWallCells = mutableMapOf<Pair<Int, Int>, String>()
        val newSideWalls = mutableSetOf<Pair<Int, Int>>()
        val newSideWallCells = mutableMapOf<Pair<Int, Int>, String>()

        // frustumAngleHalf is the maximum visible angle: a ray to the far corner of the outermost
        // slot (lat = ±fovHalf at depth = viewDistance) has angle ±fovHalf/viewDistance.
        val frustumAngleHalf = fovHalf / viewDistance
        val covered = mutableListOf<Pair<Float, Float>>()

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
                // sideDepth=0 is skipped to avoid dividing by zero; covered is still empty at
                // that point so the check would always pass anyway.
                if (sideDepth > 0) {
                    val angleMin = minOf(xB / D, xB / sideDepth).coerceAtLeast(-frustumAngleHalf)
                    val angleMax = maxOf(xB / D, xB / sideDepth).coerceAtMost(frustumAngleHalf)
                    if (angleMin >= angleMax) continue
                    if (subtractCoverage(angleMin to angleMax, covered).isEmpty()) continue
                }

                log("updateWalls", "add side wall lat=$wallLat, depth=$sideDepth")
                newSideWalls.add(k to sideDepth)
                val (wCellX, wCellY) = if (leftIsWall) leftCellX to leftCellY else rightCellX to rightCellY
                newSideWallCells[wCellX to wCellY] = "$k,$sideDepth"
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

                // Angular occlusion check: this slot occupies angles [latLeft/D, latRight/D]
                // clipped to the frustum. If the entire interval is already covered by closer
                // walls, the face is invisible.
                val angleLeft = maxOf(latLeft / D, -frustumAngleHalf)
                val angleRight = minOf(latRight / D, frustumAngleHalf)
                if (angleLeft >= angleRight) continue
                if (subtractCoverage(angleLeft to angleRight, covered).isEmpty()) continue

                // Open→wall transition: only emit the face if the cell at depth D-1 is open.
                // Walls with no exposed face are skipped here but still add to coverage in step 3.
                val prevCellX = viewer.cellX + (D - 1) * viewer.facing.dx + lat * right.dx
                val prevCellY = viewer.cellY + (D - 1) * viewer.facing.dy + lat * right.dy
                if (tileMapManager.tileMap.cellTypeAt(prevCellX, prevCellY) != CellType.WALL) {
                    log("updateWalls", "add front wall $lat, $D")
                    newWalls.add(lat to D)
                    newFrontWallCells[cellX to cellY] = "$lat,$D"
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
        }

        _frontWallCells.value = newFrontWallCells
        _sideWallCells.value = newSideWallCells

        syncFrontWallActors(newWalls, viewW, viewH)
        syncSideWallActors(newSideWalls, viewW, viewH)
    }

    // Diff-based actor lifecycle management for front walls.
    //
    // Compares the newly computed visibility set against the currently live actors:
    // removes actors whose (lat, depth) slot is no longer visible, and creates actors
    // for newly visible slots. Slots present in both sets are left untouched (no
    // recreation cost when the player hasn't moved).
    //
    // Projection: at depth D each slot is viewW*viewDistance/(fovWidth*D) wide and
    // viewH*wallHeightScale/D tall, centred at lat * slotWidth. Closer walls use a
    // higher layerIndex (+1 offset keeps front walls above same-depth side walls).
    private fun syncFrontWallActors(newWalls: Set<Pair<Int, Int>>, viewW: Float, viewH: Float) {
        wallActors.keys.filter { it !in newWalls }
            .forEach { key -> wallActors.remove(key)?.let { actorManager.remove(it) } }

        newWalls.filter { it !in wallActors }.forEach { key ->
            val (lat, dep) = key
            val slotWidth = viewW * viewDistance / (fovWidth * dep)
            val actor = FrontWallActor(
                centerX = lat * slotWidth,
                width = slotWidth,
                height = viewH * wallHeightScale / dep,
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

    // Diff-based actor lifecycle management for side walls.
    //
    // Same diff pattern as syncFrontWallActors. Side wall geometry is a trapezoid:
    //   xNear / yNearHalf — the party-side edge (wider, taller)
    //   xFar  / yFarHalf  — the far edge (narrower, shorter)
    // Both are derived from the perspective projection xB * viewW * viewDistance / (fovWidth * depth).
    //
    // Special cases:
    //   depth=0: xNear formula diverges → clamp to ±viewW/2. yNearHalf is then derived from the
    //     same perspective line as depth≥1 strips (y/|x| = constant) to avoid a visible bulge.
    //   xNear overshoot: when xNear projects past ±viewW/2, clamp it and linearly interpolate
    //     yNearHalf so the trapezoid meets the screen edge at the correct height.
    private fun syncSideWallActors(newSideWalls: Set<Pair<Int, Int>>, viewW: Float, viewH: Float) {
        sideWallActors.keys.filter { it !in newSideWalls }
            .forEach { key -> sideWallActors.remove(key)?.let { actorManager.remove(it) } }

        newSideWalls.filter { it !in sideWallActors }.forEach { key ->
            val (k, depth) = key
            val xB = k / 2f
            val xNear = if (depth == 0) (if (xB > 0f) viewW / 2f else -viewW / 2f)
                        else xB * viewW * viewDistance / (fovWidth * depth)
            val xFar = xB * viewW * viewDistance / (fovWidth * (depth + 1))
            val yFarHalf = viewH * wallHeightScale / (2f * (depth + 1))
            // Perspective-line invariant: y/|x| is constant for depth≥1 edges. At depth=0 the
            // x formula is clamped, so derive yNearHalf from the same line rather than viewH/2.
            val yNearHalf = if (depth == 0) (viewW / 2f) * yFarHalf / kotlin.math.abs(xFar)
                            else viewH * wallHeightScale / (2f * depth)
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

    // Angular interval subtraction — the read side of the occlusion buffer.
    //
    // Returns the sub-intervals of [interval] not yet covered by any entry in [covered].
    // An empty result means the interval is fully occluded; a non-empty result means at least
    // part of it is still visible. The caller only needs to know whether the result is empty
    // or not — the actual sub-intervals are not used further.
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
