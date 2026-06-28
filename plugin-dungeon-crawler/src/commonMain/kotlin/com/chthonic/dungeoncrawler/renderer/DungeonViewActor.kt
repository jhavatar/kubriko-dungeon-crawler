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

    var drawCommands: List<DrawCommand> = emptyList()

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
        val leftIsEdge = cmd.xLeft == cmd.xWallLeft
        val rightIsEdge = cmd.xRight == cmd.xWallRight
        when (mode) {
            RenderMode.WIREFRAME -> {
                val c = Color(0xFF6B4F3B)
                drawLine(c, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xRight, cmd.yTop), 4f)
                drawLine(c, Offset(cmd.xLeft, cmd.yBottom), Offset(cmd.xRight, cmd.yBottom), 4f)
                if (leftIsEdge) drawLine(c, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xLeft, cmd.yBottom), 4f)
                if (rightIsEdge) drawLine(c, Offset(cmd.xRight, cmd.yTop), Offset(cmd.xRight, cmd.yBottom), 4f)
            }
            RenderMode.TEXTURED -> {
                drawRect(color = cmd.color, topLeft = Offset(cmd.xLeft, cmd.yTop), size = Size(cmd.xRight - cmd.xLeft, cmd.yBottom - cmd.yTop))
                val c = Color(0f, 0f, 0f, 0.4f)
                drawLine(c, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xRight, cmd.yTop), 2f)
                drawLine(c, Offset(cmd.xLeft, cmd.yBottom), Offset(cmd.xRight, cmd.yBottom), 2f)
                if (leftIsEdge) drawLine(c, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xLeft, cmd.yBottom), 2f)
                if (rightIsEdge) drawLine(c, Offset(cmd.xRight, cmd.yTop), Offset(cmd.xRight, cmd.yBottom), 2f)
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
                    val c = Color(0xFF4A3728)
                    // Slopes drawn as lines — clipRect cuts them cleanly at sub-interval
                    // bounds without a vertical stroke appearing at the clip edge.
                    drawLine(c, Offset(cmd.xNear, cmd.yNearTop), Offset(cmd.xFar, cmd.yFarTop), 4f)
                    drawLine(c, Offset(cmd.xNear, cmd.yNearBot), Offset(cmd.xFar, cmd.yFarBot), 4f)
                    if (nearInClip) drawLine(c, Offset(cmd.xNear, cmd.yNearTop), Offset(cmd.xNear, cmd.yNearBot), 4f)
                    if (farInClip) drawLine(c, Offset(cmd.xFar, cmd.yFarTop), Offset(cmd.xFar, cmd.yFarBot), 4f)
                }
                RenderMode.TEXTURED -> {
                    drawPath(Path().apply {
                        moveTo(cmd.xNear, cmd.yNearTop)
                        lineTo(cmd.xNear, cmd.yNearBot)
                        lineTo(cmd.xFar, cmd.yFarBot)
                        lineTo(cmd.xFar, cmd.yFarTop)
                        close()
                    }, color = cmd.color)
                    val c = Color(0f, 0f, 0f, 0.4f)
                    drawLine(c, Offset(cmd.xNear, cmd.yNearTop), Offset(cmd.xFar, cmd.yFarTop), 2f)
                    drawLine(c, Offset(cmd.xNear, cmd.yNearBot), Offset(cmd.xFar, cmd.yFarBot), 2f)
                    if (nearInClip) drawLine(c, Offset(cmd.xNear, cmd.yNearTop), Offset(cmd.xNear, cmd.yNearBot), 2f)
                    if (farInClip) drawLine(c, Offset(cmd.xFar, cmd.yFarTop), Offset(cmd.xFar, cmd.yFarBot), 2f)
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
}
