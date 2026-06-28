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
    data class FloorCeilingBand(
        val yFloorClipTop: Float,    // upper edge of floor strip (closer to horizon)
        val yFloorClipBottom: Float, // lower edge of floor strip (closer to screen bottom)
        val xClipLeft: Float,        // left DrawScope x bound (angular sub-interval start)
        val xClipRight: Float,       // right DrawScope x bound (angular sub-interval end)
        val floorColor: Color,
        val ceilColor: Color,
        val floorTileIndex: Int = 0,
        val ceilTileIndex: Int = 0,
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
        val shadeFactor: Float = 1f,
        // Depths in scene units — used by TEXTURED mode for perspective-correct UV interpolation.
        val zNear: Float = 1f,
        val zFar: Float = 2f,
        val debugLabel: TextLayoutResult? = null,
    ) : DrawCommand()
}
