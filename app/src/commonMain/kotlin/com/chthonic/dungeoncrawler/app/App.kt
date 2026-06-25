package com.chthonic.dungeoncrawler.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.chthonic.dungeoncrawler.renderer.DungeonRendererManager
import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.Mob
import com.chthonic.dungeoncrawler.tilemap.TileMap
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.Kubriko
import com.pandulapeter.kubriko.KubrikoViewport
import com.pandulapeter.kubriko.keyboardInput.KeyboardInputManager

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
    val kubriko = remember {
        Kubriko.newInstance(
            tileMapManager,
            DungeonRendererManager(viewer = viewer),
            PlayerManager(viewer = viewer, tileMapManager = tileMapManager, isLoggingEnabled = true),
            KeyboardInputManager.newInstance(),
            instanceNameForLogging = "DungeonCrawlerPoc",
        )
    }
    KubrikoViewport(kubriko = kubriko)
}
