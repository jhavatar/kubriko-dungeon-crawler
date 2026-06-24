package com.chthonic.dungeoncrawler.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.chthonic.dungeoncrawler.renderer.DungeonRendererManager
import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.GridActor
import com.chthonic.dungeoncrawler.tilemap.TileMap
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.Kubriko
import com.pandulapeter.kubriko.KubrikoViewport

@Composable
fun DungeonCrawlerApp() {
    val viewer = remember { GridActor(initialCellX = 2, initialCellY = 0, initialFacing = Facing.NORTH) }
    val tileMapManager = remember { TileMapManager(initialTileMap = TileMap(width = 5, height = 5)) }
    val dungeonRendererManager = remember { DungeonRendererManager(viewer = viewer) }
    val kubriko = remember {
        Kubriko.newInstance(
            tileMapManager,
            dungeonRendererManager,
            instanceNameForLogging = "DungeonCrawlerPoc",
        )
    }
    KubrikoViewport(kubriko = kubriko)
}
