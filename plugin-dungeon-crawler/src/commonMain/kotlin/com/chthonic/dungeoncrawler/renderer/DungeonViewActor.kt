package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    private val atlas: DungeonAtlas? = null,
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

    // Pre-allocated Matrix and Paint reused every frame for side-wall perspective drawing.
    private val perspectiveMatrix = Matrix()
    private val imagePaint = Paint()

    internal fun update(commands: List<DrawCommand>) {
        drawCommands = commands
    }

    override fun DrawScope.draw() {
        val mode = renderMode()
        // Pass 1: floor and ceiling — drawn before walls so walls always paint on top.
        // The occlusion-based culling already prevents floor/ceiling from occupying wall
        // pixels, but command order in the buffer would otherwise draw them over side walls.
        if (mode != RenderMode.WIREFRAME) {
            for (cmd in drawCommands) {
                if (cmd is DrawCommand.FloorCeilingBand) {
                    if (mode == RenderMode.TEXTURED && atlas != null) drawFloorCeilingTextured(cmd)
                    else drawFloorCeiling(cmd)
                }
            }
        }
        // Pass 2: walls.
        for (cmd in drawCommands) {
            when (cmd) {
                is DrawCommand.FrontStrip -> drawFrontStrip(cmd, mode)
                is DrawCommand.SideStrip -> drawSideStrip(cmd, mode)
                is DrawCommand.FloorCeilingBand -> {}
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

    private fun DrawScope.drawFloorCeilingTextured(cmd: DrawCommand.FloorCeilingBand) {
        val a = atlas ?: return
        val xLeft  = cmd.xClipLeft.toInt()
        val bandW  = (cmd.xClipRight - cmd.xClipLeft).toInt().coerceAtLeast(1)
        // Round top down and bottom up so adjacent depth bands share a pixel edge with no gap.
        val floorTop    = cmd.yFloorClipTop.toInt()
        val floorBottom = kotlin.math.ceil(cmd.yFloorClipBottom).toInt()
        val bandH = (floorBottom - floorTop).coerceAtLeast(1)
        drawImage(
            image = a.image,
            srcOffset = a.srcOffset(cmd.floorTileIndex),
            srcSize = a.srcSize(),
            dstOffset = IntOffset(xLeft, floorTop),
            dstSize = IntSize(bandW, bandH),
        )
        val ceilTop    = (size.height - cmd.yFloorClipBottom).toInt()
        val ceilBottom = kotlin.math.ceil(size.height - cmd.yFloorClipTop).toInt()
        val ceilH = (ceilBottom - ceilTop).coerceAtLeast(1)
        drawImage(
            image = a.image,
            srcOffset = a.srcOffset(cmd.ceilTileIndex),
            srcSize = a.srcSize(),
            dstOffset = IntOffset(xLeft, ceilTop),
            dstSize = IntSize(bandW, ceilH),
        )
    }

    private fun DrawScope.drawFrontStrip(cmd: DrawCommand.FrontStrip, mode: RenderMode) {
        val leftIsEdge  = cmd.xLeft  == cmd.xWallLeft
        val rightIsEdge = cmd.xRight == cmd.xWallRight
        when (mode) {
            RenderMode.WIREFRAME -> {
                drawLine(WIREFRAME_FRONT_COLOR, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xRight, cmd.yTop), WIREFRAME_STROKE)
                drawLine(WIREFRAME_FRONT_COLOR, Offset(cmd.xLeft, cmd.yBottom), Offset(cmd.xRight, cmd.yBottom), WIREFRAME_STROKE)
                if (leftIsEdge) drawLine(WIREFRAME_FRONT_COLOR, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xLeft, cmd.yBottom), WIREFRAME_STROKE)
                if (rightIsEdge) drawLine(WIREFRAME_FRONT_COLOR, Offset(cmd.xRight, cmd.yTop), Offset(cmd.xRight, cmd.yBottom), WIREFRAME_STROKE)
            }
            RenderMode.SOLID -> {
                drawRect(color = cmd.color, topLeft = Offset(cmd.xLeft, cmd.yTop), size = Size(cmd.xRight - cmd.xLeft, cmd.yBottom - cmd.yTop))
                drawLine(BORDER_COLOR, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xRight, cmd.yTop), BORDER_STROKE)
                drawLine(BORDER_COLOR, Offset(cmd.xLeft, cmd.yBottom), Offset(cmd.xRight, cmd.yBottom), BORDER_STROKE)
                if (leftIsEdge) drawLine(BORDER_COLOR, Offset(cmd.xLeft, cmd.yTop), Offset(cmd.xLeft, cmd.yBottom), BORDER_STROKE)
                if (rightIsEdge) drawLine(BORDER_COLOR, Offset(cmd.xRight, cmd.yTop), Offset(cmd.xRight, cmd.yBottom), BORDER_STROKE)
            }
            RenderMode.TEXTURED -> {
                val a = atlas
                if (a != null) {
                    // A partially-occluded front wall must show only the corresponding slice
                    // of the texture — not the full tile stretched to the visible sub-interval.
                    // Compute u_left/u_right as the fraction of [xLeft,xRight] within the
                    // full wall [xWallLeft,xWallRight] and sample only that portion.
                    val wallW  = cmd.xWallRight - cmd.xWallLeft
                    val uLeft  = if (wallW > 0f) (cmd.xLeft  - cmd.xWallLeft) / wallW else 0f
                    val uRight = if (wallW > 0f) (cmd.xRight - cmd.xWallLeft) / wallW else 1f
                    val tileLeft = (cmd.tileIndex % a.cols) * a.tileSize
                    val tileTop  = (cmd.tileIndex / a.cols) * a.tileSize
                    val srcX = (tileLeft + uLeft  * a.tileSize).toInt()
                    val srcW = ((uRight - uLeft) * a.tileSize).toInt().coerceAtLeast(1)
                    drawImage(
                        image = a.image,
                        srcOffset = IntOffset(srcX, tileTop),
                        srcSize = IntSize(srcW, a.tileSize),
                        dstOffset = IntOffset(cmd.xLeft.toInt(), cmd.yTop.toInt()),
                        dstSize = IntSize(
                            (cmd.xRight - cmd.xLeft).toInt().coerceAtLeast(1),
                            (cmd.yBottom - cmd.yTop).toInt().coerceAtLeast(1),
                        ),
                    )
                } else {
                    drawRect(color = cmd.color, topLeft = Offset(cmd.xLeft, cmd.yTop), size = Size(cmd.xRight - cmd.xLeft, cmd.yBottom - cmd.yTop))
                }
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
        val nearInClip = cmd.xNear in cmd.xClipLeft..cmd.xClipRight
        val farInClip  = cmd.xFar  in cmd.xClipLeft..cmd.xClipRight
        clipRect(left = cmd.xClipLeft, top = cmd.yNearTop, right = cmd.xClipRight, bottom = cmd.yNearBot) {
            when (mode) {
                RenderMode.WIREFRAME -> {
                    drawLine(WIREFRAME_SIDE_COLOR, Offset(cmd.xNear, cmd.yNearTop), Offset(cmd.xFar, cmd.yFarTop), WIREFRAME_STROKE)
                    drawLine(WIREFRAME_SIDE_COLOR, Offset(cmd.xNear, cmd.yNearBot), Offset(cmd.xFar, cmd.yFarBot), WIREFRAME_STROKE)
                    if (nearInClip) drawLine(WIREFRAME_SIDE_COLOR, Offset(cmd.xNear, cmd.yNearTop), Offset(cmd.xNear, cmd.yNearBot), WIREFRAME_STROKE)
                    if (farInClip) drawLine(WIREFRAME_SIDE_COLOR, Offset(cmd.xFar, cmd.yFarTop), Offset(cmd.xFar, cmd.yFarBot), WIREFRAME_STROKE)
                }
                RenderMode.SOLID -> {
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
                RenderMode.TEXTURED -> {
                    val a = atlas
                    if (a != null) {
                        // Perspective-correct texture mapping via canvas homography.
                        //
                        // Instead of triangles with affine UV interpolation (which creates
                        // "wavy" distortion because both U and V are wrong inside triangles
                        // when near/far heights differ), we compute the exact 3×3 perspective
                        // homography H that maps tile pixel coords → viewport coords and set
                        // it as canvas.concat(H). Skia and Android Canvas both apply
                        // perspective-correct pixel sampling when a perspective matrix is
                        // active, giving accurate texture mapping with a single drawImage call.
                        //
                        // Our trapezoid has two vertical parallel sides (left at xNear,
                        // right at xFar), which means h=0 in the quad-to-unit-square formula
                        // and the homography reduces to a simple closed form — no linear
                        // system solver required.
                        fillPerspectiveMatrix(cmd, a)
                        trapezoidPath.reset()
                        trapezoidPath.moveTo(cmd.xNear, cmd.yNearTop)
                        trapezoidPath.lineTo(cmd.xNear, cmd.yNearBot)
                        trapezoidPath.lineTo(cmd.xFar,  cmd.yFarBot)
                        trapezoidPath.lineTo(cmd.xFar,  cmd.yFarTop)
                        trapezoidPath.close()
                        drawIntoCanvas { canvas ->
                            canvas.save()
                            // angular-interval clip already applied by outer clipRect;
                            // add the trapezoid shape clip so atlas pixels outside the
                            // tile area don't bleed past the wall edges
                            canvas.clipPath(trapezoidPath)
                            canvas.concat(perspectiveMatrix)
                            canvas.drawImage(a.image, Offset.Zero, imagePaint)
                            canvas.restore()
                        }
                    } else {
                        trapezoidPath.reset()
                        trapezoidPath.moveTo(cmd.xNear, cmd.yNearTop)
                        trapezoidPath.lineTo(cmd.xNear, cmd.yNearBot)
                        trapezoidPath.lineTo(cmd.xFar,  cmd.yFarBot)
                        trapezoidPath.lineTo(cmd.xFar,  cmd.yFarTop)
                        trapezoidPath.close()
                        drawPath(trapezoidPath, color = cmd.color)
                    }
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

    // Fills perspectiveMatrix in-place with the homography that maps atlas tile pixel
    // coordinates to viewport (DrawScope) coordinates, matching the trapezoid corners.
    //
    // Derivation: the trapezoid has corners
    //   TL=(xNear,yNearTop), TR=(xFar,yFarTop), BR=(xFar,yFarBot), BL=(xNear,yNearBot)
    //
    // The unit-square → quad homography formula (standard quad-to-unit-square) simplifies
    // when the left side (xNear) and right side (xFar) are both vertical:
    //   dx1 = xFar-xFar = 0, sx = xNear-xFar+xFar-xNear = 0  →  h=0, b=0
    //
    // Only g (the perspective W component) is non-trivially determined:
    //   g = sy / dy1  where
    //       sy  = yNearTop - yFarTop + yFarBot - yNearBot  (= nearHeight - farHeight)
    //       dy1 = yFarTop  - yFarBot                       (negative; far wall top-to-bot)
    //
    // The unit-square homography H_unit is then composed with the tile-to-unit-square
    // affine S⁻¹ (scale by 1/tileSize, translate by -tileLeft/-tileTop) to give H_total.
    //
    // H_total is stored in Compose's column-major 4×4 Matrix so that the perspective
    // W row (Perspective0/1/2 = values[3/7/15]) is preserved through Compose's
    // Matrix → Android 3×3 / Skia 4×4 conversion.
    //
    // U-axis aspect correction: for narrow strips (strip width << strip height) the full
    // tile would be compressed into very few horizontal pixels, making texels appear tall
    // and thin. ts_u < ts limits the visible tile range in U so texels appear square at
    // the far (smaller) end of the trapezoid. The V axis always uses ts (full tile height).
    private fun fillPerspectiveMatrix(cmd: DrawCommand.SideStrip, a: DungeonAtlas) {
        val tileLeft = (cmd.tileIndex % a.cols) * a.tileSize.toFloat()
        val tileTop  = (cmd.tileIndex / a.cols) * a.tileSize.toFloat()
        val ts = a.tileSize.toFloat()

        val g   = (cmd.yNearTop - cmd.yFarTop + cmd.yFarBot - cmd.yNearBot) /
                  (cmd.yFarTop  - cmd.yFarBot)
        val aV  = cmd.xFar - cmd.xNear + g * cmd.xFar
        val dV  = cmd.yFarTop - cmd.yNearTop + g * cmd.yFarTop
        val eV  = cmd.yNearBot - cmd.yNearTop

        val stripH = cmd.yFarBot - cmd.yFarTop
        val uAspect = if (stripH > 0f) (kotlin.math.abs(cmd.xFar - cmd.xNear) / stripH).coerceAtMost(1f) else 1f
        val ts_u = ts * uAspect  // effective U tile width; ts_u ≤ ts

        // H_total = H_unit · S⁻¹  (maps tile pixel → viewport; U uses ts_u, V uses ts)
        val h00 = aV / ts_u
        val h10 = dV / ts_u
        val h20 = g  / ts_u
        val h01 = 0f
        val h11 = eV / ts
        val h21 = 0f
        val h02 = -aV * tileLeft / ts_u + cmd.xNear
        val h12 = -dV * tileLeft / ts_u - eV * tileTop / ts + cmd.yNearTop
        val h22 = -g  * tileLeft / ts_u + 1f

        // Compose Matrix is column-major 4×4.
        // 2D perspective embedding: Z row/column is identity pass-through.
        // values layout: col0=(0..3), col1=(4..7), col2=(8..11), col3=(12..15)
        val v = perspectiveMatrix.values
        v[0]  = h00;  v[1]  = h10;  v[2]  = 0f;  v[3]  = h20   // col 0
        v[4]  = h01;  v[5]  = h11;  v[6]  = 0f;  v[7]  = h21   // col 1
        v[8]  = 0f;   v[9]  = 0f;   v[10] = 1f;  v[11] = 0f    // col 2 (Z)
        v[12] = h02;  v[13] = h12;  v[14] = 0f;  v[15] = h22   // col 3
    }

    private companion object {
        val WIREFRAME_FRONT_COLOR = Color(0xFF6B4F3B)
        val WIREFRAME_SIDE_COLOR  = Color(0xFF4A3728)
        val BORDER_COLOR          = Color(0f, 0f, 0f, 0.4f)
        const val WIREFRAME_STROKE = 4f
        const val BORDER_STROKE    = 2f
    }
}
