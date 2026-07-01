package com.chthonic.dungeoncrawler.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The six navigation actions exposed as an on-screen control grid, mirroring the W/A/S/D/Q/E keyboard mapping. */
enum class NavAction { TurnLeft, Forward, TurnRight, StrafeLeft, Backward, StrafeRight }

// Reference (design-resolution) button size — the whole HUD (viewport + this grid + the
// minimap) is laid out proportionally to these and scaled together as one unit by the caller,
// so there is no independent min/max clamp here: whatever square this composable is actually
// given, all six buttons scale evenly to fill it.
internal val NAV_BUTTON_SPACING = 8.dp
private val NAV_BUTTON_SIZE = 56.dp

// The 3-column grid's reference width — callers (e.g. the minimap, which must match this grid's
// size) size themselves against this rather than duplicating the button math.
internal val NAV_GRID_SIZE = NAV_BUTTON_SIZE * 3 + NAV_BUTTON_SPACING * 2

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
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // All six buttons scale together from the space actually given to this composable, so
        // the grid never overflows its container nor shrinks unevenly. Fitting both the 3-column
        // width and the 2-row height (rather than width alone) means this composable's overall
        // footprint can be squared off to match another element (e.g. the minimap) exactly —
        // whichever axis has slack just centers the grid instead of stretching it.
        val buttonSize = minOf(
            (maxWidth - NAV_BUTTON_SPACING * 2) / 3,
            (maxHeight - NAV_BUTTON_SPACING) / 2,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(NAV_BUTTON_SPACING)) {
                NavButton(NavAction.TurnLeft, NavAction.TurnLeft in pressedActions, buttonSize, onTurnLeft)
                NavButton(NavAction.Forward, NavAction.Forward in pressedActions, buttonSize, onMoveForward)
                NavButton(NavAction.TurnRight, NavAction.TurnRight in pressedActions, buttonSize, onTurnRight)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NAV_BUTTON_SPACING)) {
                NavButton(NavAction.StrafeLeft, NavAction.StrafeLeft in pressedActions, buttonSize, onStrafeLeft)
                NavButton(NavAction.Backward, NavAction.Backward in pressedActions, buttonSize, onMoveBackward)
                NavButton(NavAction.StrafeRight, NavAction.StrafeRight in pressedActions, buttonSize, onStrafeRight)
            }
        }
    }
}

@Composable
private fun NavButton(action: NavAction, isKeyHeld: Boolean, buttonSize: Dp, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPointerPressed by interactionSource.collectIsPressedAsState()
    val isPressed = isPointerPressed || isKeyHeld
    val backgroundColor = if (isPressed) Color(0xFF6B4F3B) else Color(0xFF3B2C1F)
    val borderColor = if (isPressed) Color(0xFFFFEE44) else Color(0xFF8B6B52)
    Canvas(
        modifier = Modifier
            .size(buttonSize)
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
