package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult

// A pre-computed draw operation for one visual element of the dungeon view.
//
// All coordinates are in DungeonViewActor DrawScope space: origin = viewport top-left,
// x increases rightward, y increases downward. Horizon sits at (viewW/2, viewH/2).
//
// Commands are built by DungeonRendererManager on position/viewport change and
// executed in order by DungeonViewActor every frame. When nothing has changed, the
// list is identical and the GPU sees the same draw calls — no actor churn.
sealed class DrawCommand {

    // One visible cell-slot of floor and ceiling.
    //
    // The floor strip occupies [yFloorClipTop, yFloorClipBottom] × [xClipLeft, xClipRight].
    // The ceiling strip is its vertical mirror around the horizon:
    //   top = viewH - yFloorClipBottom, bottom = viewH - yFloorClipTop.
    //
    // Emitted once per open (lat, depth) slot after angular-occlusion culling.
    // floorColor / ceilColor are flat per-cell tints — replace with texture references when
    // real floor/ceiling textures are added.
    //
    // xNear*/xFar*: full perspective quad corner x-positions for texture mapping.
    //   yFloorClipBottom = near edge (depth D, closer to player)
    //   yFloorClipTop    = far edge  (depth D+1, closer to horizon)
    data class FloorCeilingBand(
        val yFloorClipTop: Float,    // upper edge of floor strip (closer to horizon)
        val yFloorClipBottom: Float, // lower edge of floor strip (closer to screen bottom)
        val xClipLeft: Float,        // left DrawScope x bound (angular sub-interval start)
        val xClipRight: Float,       // right DrawScope x bound (angular sub-interval end)
        val floorColor: Color,
        val ceilColor: Color,
        val xNearLeft: Float,        // x at near edge (depth D), left cell boundary
        val xNearRight: Float,       // x at near edge (depth D), right cell boundary
        val xFarLeft: Float,         // x at far edge (depth D+1), left cell boundary
        val xFarRight: Float,        // x at far edge (depth D+1), right cell boundary
        val floorTileIndex: Int = 0,
        val ceilTileIndex: Int = 0,
        // Fraction of the tile V range shown from far edge (V=0) to near edge (V=vNearFraction*ts).
        // 1.0 for depth-D bands (full tile per cell). For the near band: (1−wallHeightScale),
        // because only the depth 0.8→1 portion of the player's cell (depth 0→1) is on screen.
        val vNearFraction: Float = 1f,
        // Near/far brightness for the TEXTURED-mode gradient (applied via BlendMode.Multiply).
        // "Near" = player-side edge; "far" = horizon-side edge. A continuous gradient is drawn
        // between them so adjacent depth bands connect seamlessly.
        val floorNearBrightness: Float = 1f,
        val floorFarBrightness: Float = 1f,
        val ceilNearBrightness: Float = 1f,
        val ceilFarBrightness: Float = 1f,
    ) : DrawCommand()

    // A visible sub-interval strip of a front-facing wall.
    // xLeft/xRight are the DrawScope x clip bounds for the uncovered angular sub-interval.
    // The wall fills [yTop, yBottom] across the full band height.
    data class FrontStrip(
        val xLeft: Float,
        val xRight: Float,
        val yTop: Float,
        val yBottom: Float,
        val color: Color,
        // Full wall x extents (before sub-interval clipping). Used to distinguish real
        // cell-geometry edge borders from occlusion-seam clip edges (no border at seam).
        val xWallLeft: Float,
        val xWallRight: Float,
        val tileIndex: Int = 0,
        // 0..1 brightness used in TEXTURED mode (applied via grayscale ColorFilter.Multiply).
        val brightness: Float = 1f,
        val debugLabel: TextLayoutResult? = null,
    ) : DrawCommand()

    // A visible sub-interval strip of a side-wall trapezoid, clipped to [xClipLeft, xClipRight].
    // The full trapezoid spans (xNear, yNearTop..yNearBot) → (xFar, yFarTop..yFarBot).
    // clipRect restricts drawing to the uncovered angular sub-interval — no polygon clipping needed.
    data class SideStrip(
        val xNear: Float,
        val xFar: Float,
        val yNearTop: Float,
        val yNearBot: Float,
        val yFarTop: Float,
        val yFarBot: Float,
        val xClipLeft: Float,
        val xClipRight: Float,
        val color: Color,
        val tileIndex: Int = 0,
        // 0..1 brightness used in TEXTURED mode (applied via grayscale ColorFilter.Multiply).
        val brightness: Float = 1f,
        // Fraction (0..1) of the tile U range that is off-screen at the near edge.
        // 0 = full tile visible from near to far (typical for sideDepth > 0).
        // > 0 = near end of the wall face is clipped by the screen edge (always the case for
        //   sideDepth=0, where the wall extends behind the player; also for large |xB| + small depth).
        val tileUNearFraction: Float = 0f,
        val debugLabel: TextLayoutResult? = null,
    ) : DrawCommand()

    // A monster's screen footprint for one visible angular sub-interval — always a flat
    // camera-facing billboard (no perspective skew, unlike SideStrip).
    //
    // xLeft/xRight are the occlusion-clipped bounds actually drawn; xSpriteLeft/xSpriteRight
    // are the full unclipped footprint, used to compute which slice of the tile is visible
    // when a monster is partially occluded (mirrors FrontStrip.xWallLeft/xWallRight).
    data class Sprite(
        val xLeft: Float,
        val xRight: Float,
        val xSpriteLeft: Float,
        val xSpriteRight: Float,
        val yTop: Float,
        val yBottom: Float,
        val tileIndex: Int,
        val color: Color,
        // 0..1 brightness used in TEXTURED mode (applied via grayscale ColorFilter.Multiply)
        // and dimmed into `color` for WIREFRAME/SOLID, matching FrontStrip/SideStrip.
        val brightness: Float = 1f,
        // Depth of the monster's own cell. Sprites — unlike walls — can overlap each other in
        // screen space, so DungeonViewActor sorts by this for back-to-front painter's-algorithm
        // ordering; see DungeonViewActor's Pass 3.
        val depth: Int,
    ) : DrawCommand()
}
