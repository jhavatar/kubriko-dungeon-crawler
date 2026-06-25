package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.pandulapeter.kubriko.actor.Actor
import com.pandulapeter.kubriko.actor.body.BoxBody
import com.pandulapeter.kubriko.actor.traits.Visible
import com.pandulapeter.kubriko.helpers.extensions.sceneUnit
import com.pandulapeter.kubriko.types.SceneOffset
import com.pandulapeter.kubriko.types.SceneSize

class WallSlotActor(
    centerX: Float,
    width: Float,
    height: Float,
    private val depth: Int,
    private val viewDistance: Int,
    private val renderMode: () -> RenderMode,
    override val layerIndex: Int,
) : Actor, Visible {

    override val body = BoxBody(
        initialPosition = SceneOffset(centerX.sceneUnit, 0f.sceneUnit),
        initialSize = SceneSize(width.sceneUnit, height.sceneUnit),
    )

    override fun DrawScope.draw() {
        val color = computeColor()
        when (renderMode()) {
            RenderMode.WIREFRAME -> drawRect(color = color, size = body.size.raw, style = Stroke(4f))
            RenderMode.TEXTURED -> drawRect(color = color, size = body.size.raw)
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
