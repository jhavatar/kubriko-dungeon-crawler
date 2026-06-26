package com.chthonic.dungeoncrawler.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chthonic.dungeoncrawler.renderer.DungeonRendererManager
import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.Mob
import com.chthonic.dungeoncrawler.tilemap.TileMap
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.Kubriko
import com.pandulapeter.kubriko.KubrikoViewport
import com.pandulapeter.kubriko.keyboardInput.KeyboardInputManager
import com.pandulapeter.kubriko.logger.Logger
import com.pandulapeter.kubriko.manager.ViewportManager

private data class ViewerSnapshot(val cellX: Int, val cellY: Int, val facing: Facing)

@Composable
fun DungeonCrawlerApp() {
    val viewer = remember { Mob(initialCellX = 2, initialCellY = 3, initialFacing = Facing.NORTH) }
    val tileMapManager = remember {
        TileMapManager(
            initialTileMap = TileMap.fromString(
                """
                #####
                #...#
                #.#.#
                #...#
                #####
                """
            )
        )
    }

    var viewerSnapshot by remember { mutableStateOf(ViewerSnapshot(viewer.cellX, viewer.cellY, viewer.facing)) }

    val playerManager = remember {
        PlayerManager(
            viewer = viewer,
            tileMapManager = tileMapManager,
            onViewerChanged = { viewerSnapshot = ViewerSnapshot(viewer.cellX, viewer.cellY, viewer.facing) },
            isLoggingEnabled = true,
        )
    }
    val dungeonRenderer = remember { DungeonRendererManager(viewer = viewer, isLoggingEnabled = true) }
    val kubriko = remember {
        Kubriko.newInstance(
            tileMapManager,
            dungeonRenderer,
            playerManager,
            KeyboardInputManager.newInstance(),
            ViewportManager.newInstance(),
            instanceNameForLogging = "DungeonCrawlerPoc",
        )
    }
    LaunchedEffect(Unit) {
        Logger.latestEntry.collect { entry ->
            println("[${entry.source}] ${entry.message}${entry.details?.let { " — $it" } ?: ""}")
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val desiredWalls by dungeonRenderer.desiredWalls

    Box(modifier = Modifier.fillMaxSize()) {
        // Inner box matches the viewport so the overlay Canvas is the same size.
        Box(
            modifier = Modifier
                .fillMaxSize(0.75f)
                .align(Alignment.Center),
        ) {
            KubrikoViewport(
                kubriko = kubriko,
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, Color(0xFF8B6B52)),
            )
            // Overlay: draw the (lat, depth) label at each front-wall slot centre.
            Canvas(modifier = Modifier.fillMaxSize()) {
                desiredWalls.forEach { (lat, dep) ->
                    val slotWidth = size.width * dungeonRenderer.viewDistance / (dungeonRenderer.fovWidth * dep)
                    val centerX = size.width / 2f + lat * slotWidth
                    val centerY = size.height / 2f
                    val layout = textMeasurer.measure(
                        text = "$lat,$dep",
                        style = TextStyle(fontSize = 12.sp, color = Color.Yellow),
                    )
                    drawText(layout, topLeft = Offset(centerX - layout.size.width / 2f, centerY - layout.size.height / 2f))
                }
            }
        }
        MinimapOverlay(
            tileMap = tileMapManager.tileMap,
            cellX = viewerSnapshot.cellX,
            cellY = viewerSnapshot.cellY,
            facing = viewerSnapshot.facing,
            fovWidth = dungeonRenderer.fovWidth,
            viewDistance = dungeonRenderer.viewDistance,
            desiredWalls = desiredWalls,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(150.dp),
        )
    }
}
