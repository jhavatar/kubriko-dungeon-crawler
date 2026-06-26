package com.chthonic.dungeoncrawler.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.chthonic.dungeoncrawler.tilemap.CellType
import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.TileMap

@Composable
fun MinimapOverlay(
    tileMap: TileMap,
    cellX: Int,
    cellY: Int,
    facing: Facing,
    fovWidth: Int,
    viewDistance: Int,
    modifier: Modifier = Modifier,
) {
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

        // Frustum: apex at the back edge of the party's cell; far corners ±fovHalf right at viewDistance.
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
        drawPath(frustumPath, color = Color(0x26FFEE44))
        drawPath(frustumPath, color = Color(0xCCFFEE44), style = Stroke(width = 1f))

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
