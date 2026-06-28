package com.chthonic.dungeoncrawler.app

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
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
                ########
                #...#.##
                #.#.#.##
                #......#
                ######.#
                ####...#
                #......#
                ########
                """
            )
        )
    }

    var viewerSnapshot by remember {
        mutableStateOf(
            ViewerSnapshot(
                viewer.cellX,
                viewer.cellY,
                viewer.facing
            )
        )
    }

    val playerManager = remember {
        PlayerManager(
            viewer = viewer,
            tileMapManager = tileMapManager,
            onViewerChanged = {
                viewerSnapshot = ViewerSnapshot(viewer.cellX, viewer.cellY, viewer.facing)
            },
            isLoggingEnabled = true,
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val dungeonRenderer = remember {
        DungeonRendererManager(
            fovWidth = 5,
            viewer = viewer,
            textMeasurer = textMeasurer,
            isLoggingEnabled = true
        )
    }
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

    val frontWallCells by dungeonRenderer.frontWallCells
    val sideWallCells by dungeonRenderer.sideWallCells

    Box(modifier = Modifier.fillMaxSize()) {
        KubrikoViewport(
            kubriko = kubriko,
            modifier = Modifier
                .fillMaxSize(0.75f)
                .align(Alignment.Center)
                .border(2.dp, Color(0xFF8B6B52)),
        )
        MinimapOverlay(
            tileMap = tileMapManager.tileMap,
            cellX = viewerSnapshot.cellX,
            cellY = viewerSnapshot.cellY,
            facing = viewerSnapshot.facing,
            fovWidth = dungeonRenderer.fovWidth,
            viewDistance = dungeonRenderer.viewDistance,
            frontWallCells = frontWallCells,
            sideWallCells = sideWallCells,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(150.dp),
        )
    }
}
