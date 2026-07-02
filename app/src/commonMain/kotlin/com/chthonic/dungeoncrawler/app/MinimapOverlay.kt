package com.chthonic.dungeoncrawler.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.chthonic.dungeoncrawler.tilemap.CellType
import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.TileMap

// Immutable per-frame snapshot of a monster's position/facing for the minimap — mirrors how
// the party's own position is snapshotted into a Compose state (see App.kt's ViewerSnapshot),
// since Monster itself is mutated by MonsterManager on the engine tick, not by Compose.
data class MonsterMarker(val cellX: Int, val cellY: Int, val facing: Facing)

@Composable
fun MinimapOverlay(
    tileMap: TileMap,
    cellX: Int,
    cellY: Int,
    facing: Facing,
    fovWidth: Int,
    viewDistance: Int,
    frontWallCells: Map<Pair<Int, Int>, String> = emptyMap(),
    sideWallCells: Map<Pair<Int, Int>, String> = emptyMap(),
    visibleOpenCells: Set<Pair<Int, Int>> = emptySet(),
    monsters: List<MonsterMarker> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val cellSize = minOf(size.width / tileMap.width, size.height / tileMap.height)
        val originX = (size.width - tileMap.width * cellSize) / 2f
        val originY = (size.height - tileMap.height * cellSize) / 2f

        drawRect(Color(0xCC1A1208))

        for (y in 0 until tileMap.height) {
            for (x in 0 until tileMap.width) {
                val color = when (tileMap.cellTypeAt(x, y)) {
                    CellType.WALL -> Color(0xFF2A1E14)
                    CellType.OPEN -> Color(0xFF6B4F3B)
                    CellType.SPECIAL -> Color(0xFF3B6B4F)
                }
                drawRect(
                    color = color,
                    topLeft = Offset(originX + x * cellSize + 1f, originY + y * cellSize + 1f),
                    size = Size(cellSize - 2f, cellSize - 2f),
                )
            }
        }

        // Visibility highlight: one translucent tint per open cell that actually has an
        // on-screen pixel this frame (DungeonRendererManager.visibleOpenCells — survived
        // angular-occlusion culling, not just "inside the raw geometric FOV triangle"). Cells
        // hidden around a corner are correctly left untinted; wall cells get their own more
        // specific yellow/cyan highlight below instead of this generic tint.
        visibleOpenCells.forEach { (vx, vy) ->
            drawRect(
                color = Color(0x40FFEE44),
                topLeft = Offset(originX + vx * cellSize, originY + vy * cellSize),
                size = Size(cellSize, cellSize),
            )
        }

        // Frustum outline: apex at the back edge of the party's cell; far corners ±fovHalf
        // right at viewDistance. This is the raw geometric FOV wedge (unlike the cell-accurate
        // tint above, it doesn't account for occlusion), so it's clipped to the map's own
        // rectangle — clipRect does real line-segment clipping, not just a visibility toggle,
        // so an edge that exits the map partway through is cut off exactly at the boundary
        // instead of the whole line disappearing or overshooting into the letterboxed margin.
        val right = facing.turnedRight()
        val fovHalf = fovWidth / 2f
        val apexCellX = cellX + 0.5f - 0.5f * facing.dx
        val apexCellY = cellY + 0.5f - 0.5f * facing.dy
        val farCenterCellX = apexCellX + viewDistance * facing.dx
        val farCenterCellY = apexCellY + viewDistance * facing.dy
        val apexPx = Offset(originX + apexCellX * cellSize, originY + apexCellY * cellSize)
        val farLeft = Offset(
            originX + (farCenterCellX - fovHalf * right.dx) * cellSize,
            originY + (farCenterCellY - fovHalf * right.dy) * cellSize,
        )
        val farRight = Offset(
            originX + (farCenterCellX + fovHalf * right.dx) * cellSize,
            originY + (farCenterCellY + fovHalf * right.dy) * cellSize,
        )
        val frustumPath = Path().apply {
            moveTo(apexPx.x, apexPx.y)
            lineTo(farLeft.x, farLeft.y)
            lineTo(farRight.x, farRight.y)
            close()
        }
        clipRect(left = originX, top = originY, right = originX + tileMap.width * cellSize, bottom = originY + tileMap.height * cellSize) {
            drawPath(frustumPath, color = Color(0xCCFFEE44), style = Stroke(width = 1f))
        }

        val labelStyle = TextStyle(fontSize = 6.sp, color = Color.Black)

        // Highlight each visible side wall cell (cyan) with its (k,depth) label.
        // Skip cells already covered by a front wall highlight to avoid colour mixing.
        sideWallCells.forEach { (cellPos, label) ->
            if (cellPos in frontWallCells) return@forEach
            val (wCellX, wCellY) = cellPos
            drawRect(
                Color(0xCC00EEFF),
                topLeft = Offset(originX + wCellX * cellSize + 1f, originY + wCellY * cellSize + 1f),
                size = Size(cellSize - 2f, cellSize - 2f),
            )
            val layout = textMeasurer.measure(label, style = labelStyle)
            drawText(layout, topLeft = Offset(
                originX + (wCellX + 0.5f) * cellSize - layout.size.width / 2f,
                originY + (wCellY + 0.5f) * cellSize - layout.size.height / 2f,
            ))
        }

        // Highlight each visible front wall cell (yellow) with its (lat,depth) label.
        frontWallCells.forEach { (cellPos, label) ->
            val (wCellX, wCellY) = cellPos
            drawRect(
                Color(0xCCFFEE00),
                topLeft = Offset(originX + wCellX * cellSize + 1f, originY + wCellY * cellSize + 1f),
                size = Size(cellSize - 2f, cellSize - 2f),
            )
            val layout = textMeasurer.measure(label, style = labelStyle)
            drawText(layout, topLeft = Offset(
                originX + (wCellX + 0.5f) * cellSize - layout.size.width / 2f,
                originY + (wCellY + 0.5f) * cellSize - layout.size.height / 2f,
            ))
        }

        // Monster markers — drawn before the party marker so the party stays on top if they
        // ever share a cell. Colour matches DungeonRendererManager.MONSTER_PLACEHOLDER_COLOR
        // so the minimap dot and the 3D placeholder sprite read as the same entity.
        monsters.forEach { monster ->
            val mCx = originX + (monster.cellX + 0.5f) * cellSize
            val mCy = originY + (monster.cellY + 0.5f) * cellSize
            drawCircle(color = Color(0xFFCC334D), radius = cellSize * 0.2f, center = Offset(mCx, mCy))
            val mArrowLen = cellSize * 0.35f
            drawLine(
                color = Color(0xFFFFFFFF),
                start = Offset(mCx, mCy),
                end = Offset(mCx + monster.facing.dx * mArrowLen, mCy + monster.facing.dy * mArrowLen),
                strokeWidth = 1.5f,
            )
        }

        val partyCx = originX + (cellX + 0.5f) * cellSize
        val partyCy = originY + (cellY + 0.5f) * cellSize
        drawCircle(color = Color(0xFFFF5555), radius = cellSize * 0.25f, center = Offset(partyCx, partyCy))

        val arrowLen = cellSize * 0.45f
        drawLine(
            color = Color(0xFFFFFFFF),
            start = Offset(partyCx, partyCy),
            end = Offset(partyCx + facing.dx * arrowLen, partyCy + facing.dy * arrowLen),
            strokeWidth = 2f,
        )

        drawRect(color = Color(0xFF8B6B52), size = size, style = Stroke(width = 2f))
    }
}
