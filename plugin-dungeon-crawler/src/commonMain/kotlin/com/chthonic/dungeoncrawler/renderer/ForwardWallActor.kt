package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.pandulapeter.kubriko.actor.Actor
import com.pandulapeter.kubriko.actor.body.BoxBody
import com.pandulapeter.kubriko.actor.traits.Visible
import com.pandulapeter.kubriko.helpers.extensions.sceneUnit
import com.pandulapeter.kubriko.types.SceneOffset
import com.pandulapeter.kubriko.types.SceneSize

/**
 * Placeholder visualization of a wall directly ahead of the viewer. Stands in for the real
 * slot-based wall/floor/ceiling projection until [DungeonRendererManager] grows one.
 */
class ForwardWallActor : Actor, Visible {

    override val body = BoxBody(
        initialPosition = SceneOffset.Zero,
        initialSize = SceneSize(300f.sceneUnit, 300f.sceneUnit),
    )
    override val layerIndex = 0

    override fun DrawScope.draw() = drawRect(
        color = Color(0xFF6B4F3B),
        size = body.size.raw,
    )
}
