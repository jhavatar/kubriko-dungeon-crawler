package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.pandulapeter.kubriko.actor.Actor
import com.pandulapeter.kubriko.actor.body.BoxBody
import com.pandulapeter.kubriko.actor.traits.Visible
import com.pandulapeter.kubriko.helpers.extensions.sceneUnit
import com.pandulapeter.kubriko.types.SceneOffset
import com.pandulapeter.kubriko.types.SceneSize

/**
 * Renders one depth-strip of a side wall (the face running parallel to the viewing direction).
 *
 * The strip spans from [xNear]/[yNearHalf] (the party-side edge) to [xFar]/[yFarHalf] (the
 * far edge), projecting as a trapezoid: wider and taller near the party, narrower and shorter
 * further away.  The BoxBody covers the bounding rectangle; draw() fills the actual trapezoid.
 */
class SideWallActor(
    xNear: Float,
    xFar: Float,
    private val yNearHalf: Float,
    private val yFarHalf: Float,
    private val depth: Int,
    private val viewDistance: Int,
    private val renderMode: () -> RenderMode,
    override val layerIndex: Int,
    private val debugLabel: TextLayoutResult? = null,
) : Actor, Visible {

    private val xMin = minOf(xNear, xFar)
    private val xMax = maxOf(xNear, xFar)
    private val width = xMax - xMin
    private val height = yNearHalf * 2f

    // x of each edge in DrawScope space (origin = box top-left)
    private val nearEdgeX = xNear - xMin
    private val farEdgeX = xFar - xMin

    // y of the far corners in DrawScope space (near corners are always 0 and height)
    private val farTopY = yNearHalf - yFarHalf
    private val farBottomY = yNearHalf + yFarHalf

    override val body = BoxBody(
        initialPosition = SceneOffset(((xMin + xMax) / 2f).sceneUnit, 0f.sceneUnit),
        initialSize = SceneSize(width.sceneUnit, height.sceneUnit),
    )

    override fun DrawScope.draw() {
        if (width <= 0f || height <= 0f) return
        val path = buildTrapezoid()
        val color = computeColor()
        when (renderMode()) {
            RenderMode.WIREFRAME -> drawPath(path, color = color, style = Stroke(4f))
            RenderMode.TEXTURED -> {
                drawPath(path, color = color)
                drawPath(path, color = Color(0f, 0f, 0f, 0.4f), style = Stroke(2f))
            }
        }
        debugLabel?.let { layout ->
            drawText(
                layout,
                topLeft = Offset(
                    x = width / 2 - layout.size.width / 2,
                    y = height / 2 - layout.size.height / 2,
                ),
            )
        }
    }

    private fun buildTrapezoid() = Path().apply {
        moveTo(nearEdgeX, 0f)          // near top
        lineTo(nearEdgeX, height)       // near bottom
        lineTo(farEdgeX, farBottomY)    // far bottom
        lineTo(farEdgeX, farTopY)       // far top
        close()
    }

    private fun computeColor(): Color = when (renderMode()) {
        RenderMode.WIREFRAME -> Color(0xFF4A3728)
        RenderMode.TEXTURED -> {
            val t = 1f - depth.toFloat() / viewDistance.toFloat()
            Color(
                red = (0.18f + 0.36f * t) * 0.65f,
                green = (0.10f + 0.27f * t) * 0.65f,
                blue = (0.05f + 0.17f * t) * 0.65f,
            )
        }
    }
}
