package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

class FrontWallActor(
    centerX: Float,
    width: Float,
    height: Float,
    private val depth: Int,
    private val viewDistance: Int,
    private val renderMode: () -> RenderMode,
    override val layerIndex: Int,
    private val debugLabel: TextLayoutResult? = null,
) : Actor, Visible {

    override val body = BoxBody(
        initialPosition = SceneOffset(centerX.sceneUnit, 0f.sceneUnit),
        initialSize = SceneSize(width.sceneUnit, height.sceneUnit),
    )

    override fun DrawScope.draw() {
        val color = computeColor()
        when (renderMode()) {
            RenderMode.WIREFRAME -> drawRect(color = color, size = body.size.raw, style = Stroke(4f))
            RenderMode.TEXTURED -> {
                drawRect(color = color, size = body.size.raw)
                drawRect(color = Color(0f, 0f, 0f, 0.4f), size = body.size.raw, style = Stroke(2f))
            }
        }
        debugLabel?.let { layout ->
            drawText(
                layout,
                topLeft = Offset(
                    x = body.size.raw.width / 2 - layout.size.width / 2,
                    y = body.size.raw.height / 2 - layout.size.height / 2,
                ),
            )
        }
    }

    private fun computeColor(): Color = when (renderMode()) {
        RenderMode.WIREFRAME -> Color(0xFF6B4F3B)
        RenderMode.TEXTURED -> {
            // Nearer cells are brighter (torchlight falls off with distance)
            val t = 1f - (depth - 1f) / (viewDistance - 1f).coerceAtLeast(1f)
            Color(red = 0.18f + 0.36f * t, green = 0.10f + 0.27f * t, blue = 0.05f + 0.17f * t)
        }
    }
}
