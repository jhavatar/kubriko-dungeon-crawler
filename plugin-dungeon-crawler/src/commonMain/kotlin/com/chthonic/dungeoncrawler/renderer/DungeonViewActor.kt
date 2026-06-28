package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import com.pandulapeter.kubriko.actor.Actor
import com.pandulapeter.kubriko.actor.body.BoxBody
import com.pandulapeter.kubriko.actor.traits.Visible
import com.pandulapeter.kubriko.helpers.extensions.sceneUnit
import com.pandulapeter.kubriko.types.SceneOffset
import com.pandulapeter.kubriko.types.SceneSize

// Single actor that renders the entire dungeon view — floor, ceiling, and all visible walls.
//
// Replaces the per-slot FrontWallActor / SideWallActor approach. DungeonRendererManager
// builds a drawCommands list on each position/viewport change; this actor replays it every
// frame with no actor churn and no layerIndex painter's-algorithm ordering.
//
// DrawScope coordinate system:
//   (0, 0)          = viewport top-left
//   (viewW/2, viewH/2) = horizon / scene origin
//   (viewW, viewH)  = viewport bottom-right
class DungeonViewActor(
    viewW: Float,
    viewH: Float,
    private val renderMode: () -> RenderMode,
) : Actor, Visible {

    override val layerIndex = 0

    // Body covers the full viewport, centred on the scene origin (0,0).
    override val body = BoxBody(
        initialPosition = SceneOffset(0f.sceneUnit, 0f.sceneUnit),
        initialSize = SceneSize(viewW.sceneUnit, viewH.sceneUnit),
    )

    internal var drawCommands: List<DrawCommand> = emptyList()
        private set
    private val trapezoidPath = Path()

    internal fun update(commands: List<DrawCommand>) {
        drawCommands = commands
    }

    override fun DrawScope.draw() {
        val mode = renderMode()
        for (cmd in drawCommands) {
            when (cmd) {
                is DrawCommand.FloorCeilingBand -> if (mode == RenderMode.TEXTURED) drawFloorCeiling(cmd)
                is DrawCommand.FrontStrip -> drawFrontStrip(cmd, mode)
                is DrawCommand.SideStrip -> drawSideStrip(cmd, mode)
            }
        }
    }

    private fun DrawScope.drawFloorCeiling(cmd: DrawCommand.FloorCeilingBand) {
        val bandW = cmd.xClipRight - cmd.xClipLeft
        val bandH = cmd.yFloorClipBottom - cmd.yFloorClipTop
        drawRect(
            color = cmd.floorColor,
            topLeft = Offset(cmd.xClipLeft, cmd.yFloorClipTop),
            size = Size(bandW, bandH),
        )
        drawRect(
            color = cmd.ceilColor,
            topLeft = Offset(cmd.xClipLeft, size.height - cmd.yFloorClipBottom),
            size = Size(bandW, bandH),
        )
    }

    private fun DrawScope.drawFrontStrip(cmd: DrawCommand.FrontStrip, mode: RenderMode) {
        // Only draw a vertical border when the sub-interval boundary coincides with the
        // actual wall geometry edge — i.e. that side is not clipped by occlusion.
        // Occlusion seam edges get no border so the wall doesn't look artificially small.
        // Exact float equality is intentional: both values originate from the same
        // toDsX() call in DungeonRendererManager when the sub-interval is unoccluded.
        val leftIsEdge = cmd.xLeft == cmd.xWallLeft
        val rightIsEdge = cmd.xRight == cmd.xWallRight
        when (mode) {
            RenderMode.WIREFRAME -> {
                drawLine(WIREFRAME_FRONT_COLOR, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xRight, cmd.yTop), WIREFRAME_STROKE)
                drawLine(WIREFRAME_FRONT_COLOR, Offset(cmd.xLeft, cmd.yBottom), Offset(cmd.xRight, cmd.yBottom), WIREFRAME_STROKE)
                if (leftIsEdge) drawLine(WIREFRAME_FRONT_COLOR, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xLeft, cmd.yBottom), WIREFRAME_STROKE)
                if (rightIsEdge) drawLine(WIREFRAME_FRONT_COLOR, Offset(cmd.xRight, cmd.yTop), Offset(cmd.xRight, cmd.yBottom), WIREFRAME_STROKE)
            }
            RenderMode.TEXTURED -> {
                drawRect(color = cmd.color, topLeft = Offset(cmd.xLeft, cmd.yTop), size = Size(cmd.xRight - cmd.xLeft, cmd.yBottom - cmd.yTop))
                drawLine(BORDER_COLOR, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xRight, cmd.yTop), BORDER_STROKE)
                drawLine(BORDER_COLOR, Offset(cmd.xLeft, cmd.yBottom), Offset(cmd.xRight, cmd.yBottom), BORDER_STROKE)
                if (leftIsEdge) drawLine(BORDER_COLOR, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xLeft, cmd.yBottom), BORDER_STROKE)
                if (rightIsEdge) drawLine(BORDER_COLOR, Offset(cmd.xRight, cmd.yTop), Offset(cmd.xRight, cmd.yBottom), BORDER_STROKE)
            }
        }
        cmd.debugLabel?.let { layout ->
            drawText(
                layout,
                topLeft = Offset(
                    x = cmd.xLeft + (cmd.xRight - cmd.xLeft) / 2f - layout.size.width / 2f,
                    y = cmd.yTop + (cmd.yBottom - cmd.yTop) / 2f - layout.size.height / 2f,
                ),
            )
        }
    }

    private fun DrawScope.drawSideStrip(cmd: DrawCommand.SideStrip, mode: RenderMode) {
        // Whether the real geometry endpoints fall inside the current sub-interval clip range.
        // Clip boundaries are occlusion seams (hidden behind a closer wall), not wall edges,
        // so no border stroke should appear at them.
        val nearInClip = cmd.xNear in cmd.xClipLeft..cmd.xClipRight
        val farInClip = cmd.xFar in cmd.xClipLeft..cmd.xClipRight
        clipRect(left = cmd.xClipLeft, top = cmd.yNearTop, right = cmd.xClipRight, bottom = cmd.yNearBot) {
            when (mode) {
                RenderMode.WIREFRAME -> {
                    // Slopes drawn as lines — clipRect cuts them cleanly at sub-interval
                    // bounds without a vertical stroke appearing at the clip edge.
                    drawLine(WIREFRAME_SIDE_COLOR, Offset(cmd.xNear, cmd.yNearTop), Offset(cmd.xFar, cmd.yFarTop), WIREFRAME_STROKE)
                    drawLine(WIREFRAME_SIDE_COLOR, Offset(cmd.xNear, cmd.yNearBot), Offset(cmd.xFar, cmd.yFarBot), WIREFRAME_STROKE)
                    if (nearInClip) drawLine(WIREFRAME_SIDE_COLOR, Offset(cmd.xNear, cmd.yNearTop), Offset(cmd.xNear, cmd.yNearBot), WIREFRAME_STROKE)
                    if (farInClip) drawLine(WIREFRAME_SIDE_COLOR, Offset(cmd.xFar, cmd.yFarTop), Offset(cmd.xFar, cmd.yFarBot), WIREFRAME_STROKE)
                }
                RenderMode.TEXTURED -> {
                    trapezoidPath.reset()
                    trapezoidPath.moveTo(cmd.xNear, cmd.yNearTop)
                    trapezoidPath.lineTo(cmd.xNear, cmd.yNearBot)
                    trapezoidPath.lineTo(cmd.xFar, cmd.yFarBot)
                    trapezoidPath.lineTo(cmd.xFar, cmd.yFarTop)
                    trapezoidPath.close()
                    drawPath(trapezoidPath, color = cmd.color)
                    drawLine(BORDER_COLOR, Offset(cmd.xNear, cmd.yNearTop), Offset(cmd.xFar, cmd.yFarTop), BORDER_STROKE)
                    drawLine(BORDER_COLOR, Offset(cmd.xNear, cmd.yNearBot), Offset(cmd.xFar, cmd.yFarBot), BORDER_STROKE)
                    if (nearInClip) drawLine(BORDER_COLOR, Offset(cmd.xNear, cmd.yNearTop), Offset(cmd.xNear, cmd.yNearBot), BORDER_STROKE)
                    if (farInClip) drawLine(BORDER_COLOR, Offset(cmd.xFar, cmd.yFarTop), Offset(cmd.xFar, cmd.yFarBot), BORDER_STROKE)
                }
            }
        }
        cmd.debugLabel?.let { layout ->
            val cx = (cmd.xNear + cmd.xFar) / 2f
            val cy = (cmd.yNearTop + cmd.yNearBot) / 2f
            drawText(
                layout,
                topLeft = Offset(
                    x = cx - layout.size.width / 2f,
                    y = cy - layout.size.height / 2f,
                ),
            )
        }
    }

    private companion object {
        val WIREFRAME_FRONT_COLOR = Color(0xFF6B4F3B)
        val WIREFRAME_SIDE_COLOR  = Color(0xFF4A3728)
        val BORDER_COLOR          = Color(0f, 0f, 0f, 0.4f)
        const val WIREFRAME_STROKE = 4f
        const val BORDER_STROKE    = 2f
    }
}
