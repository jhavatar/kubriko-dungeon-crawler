package com.chthonic.dungeoncrawler.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** The six navigation actions exposed as an on-screen control grid, mirroring the W/A/S/D/Q/E keyboard mapping. */
enum class NavAction { TurnLeft, Forward, TurnRight, StrafeLeft, Backward, StrafeRight }

@Composable
fun NavigationControls(
    onTurnLeft: () -> Unit,
    onMoveForward: () -> Unit,
    onTurnRight: () -> Unit,
    onStrafeLeft: () -> Unit,
    onMoveBackward: () -> Unit,
    onStrafeRight: () -> Unit,
    pressedActions: Set<NavAction> = emptySet(),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavButton(NavAction.TurnLeft, NavAction.TurnLeft in pressedActions, onTurnLeft)
            NavButton(NavAction.Forward, NavAction.Forward in pressedActions, onMoveForward)
            NavButton(NavAction.TurnRight, NavAction.TurnRight in pressedActions, onTurnRight)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavButton(NavAction.StrafeLeft, NavAction.StrafeLeft in pressedActions, onStrafeLeft)
            NavButton(NavAction.Backward, NavAction.Backward in pressedActions, onMoveBackward)
            NavButton(NavAction.StrafeRight, NavAction.StrafeRight in pressedActions, onStrafeRight)
        }
    }
}

@Composable
private fun NavButton(action: NavAction, isKeyHeld: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPointerPressed by interactionSource.collectIsPressedAsState()
    val isPressed = isPointerPressed || isKeyHeld
    val backgroundColor = if (isPressed) Color(0xFF6B4F3B) else Color(0xFF3B2C1F)
    val borderColor = if (isPressed) Color(0xFFFFEE44) else Color(0xFF8B6B52)
    Canvas(
        modifier = Modifier
            .size(56.dp)
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .border(if (isPressed) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        val iconColor = Color(0xFFE8D9C4)
        val stroke = Stroke(width = size.minDimension * 0.09f)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.28f

        fun drawArrow(dx: Float, dy: Float) {
            val tip = Offset(cx + dx * r, cy + dy * r)
            val tail = Offset(cx - dx * r, cy - dy * r)
            drawLine(iconColor, tail, tip, strokeWidth = stroke.width, cap = StrokeCap.Round)
            // Perpendicular direction for the arrowhead barbs.
            val px = -dy
            val py = dx
            val barb = r * 0.55f
            drawLine(
                iconColor,
                tip,
                Offset(tip.x - dx * barb + px * barb, tip.y - dy * barb + py * barb),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
            drawLine(
                iconColor,
                tip,
                Offset(tip.x - dx * barb - px * barb, tip.y - dy * barb - py * barb),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }

        fun drawTurn(clockwise: Boolean) {
            // An L-shaped arrow: straight up from the bottom, then a 90-degree
            // bend towards the turn direction, ending in an arrowhead.
            val dir = if (clockwise) 1f else -1f
            val bottom = Offset(cx, cy + r)
            val corner = Offset(cx, cy - r * 0.2f)
            val tip = Offset(cx + dir * r * 1.1f, cy - r * 0.2f)
            drawLine(iconColor, bottom, corner, strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(iconColor, corner, tip, strokeWidth = stroke.width, cap = StrokeCap.Round)

            val barb = r * 0.55f
            drawLine(
                iconColor,
                tip,
                Offset(tip.x - dir * barb, tip.y - barb),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
            drawLine(
                iconColor,
                tip,
                Offset(tip.x - dir * barb, tip.y + barb),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }

        when (action) {
            NavAction.Forward -> drawArrow(0f, -1f)
            NavAction.Backward -> drawArrow(0f, 1f)
            NavAction.StrafeLeft -> drawArrow(-1f, 0f)
            NavAction.StrafeRight -> drawArrow(1f, 0f)
            NavAction.TurnLeft -> drawTurn(clockwise = false)
            NavAction.TurnRight -> drawTurn(clockwise = true)
        }
    }
}
