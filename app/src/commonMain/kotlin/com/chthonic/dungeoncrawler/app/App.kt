package com.chthonic.dungeoncrawler.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chthonic.dungeoncrawler.renderer.DungeonAtlas
import com.chthonic.dungeoncrawler.renderer.DungeonRendererManager
import com.chthonic.dungeoncrawler.renderer.Monster
import com.chthonic.dungeoncrawler.renderer.MonsterAnimation
import com.chthonic.dungeoncrawler.renderer.RenderMode
import com.chthonic.dungeoncrawler.tilemap.Facing
import com.chthonic.dungeoncrawler.tilemap.Mob
import com.chthonic.dungeoncrawler.tilemap.TileMap
import com.chthonic.dungeoncrawler.tilemap.TileMapManager
import com.pandulapeter.kubriko.Kubriko
import com.pandulapeter.kubriko.KubrikoViewport
import com.pandulapeter.kubriko.helpers.extensions.sceneUnit
import com.pandulapeter.kubriko.keyboardInput.KeyboardInputManager
import com.pandulapeter.kubriko.logger.Logger
import com.pandulapeter.kubriko.manager.ViewportManager
import kubriko_dungeon_crawler.app.generated.resources.Res
import kubriko_dungeon_crawler.app.generated.resources.dungeon_atlas
import org.jetbrains.compose.resources.imageResource

private data class ViewerSnapshot(val cellX: Int, val cellY: Int, val facing: Facing)

private fun List<Monster>.toMinimapMarkers(): List<MonsterMarker> =
    map { MonsterMarker(it.mob.cellX, it.mob.cellY, it.mob.facing) }

@Composable
fun DungeonCrawlerApp() {
    val viewer = remember { Mob(initialCellX = 2, initialCellY = 3, initialFacing = Facing.NORTH) }
    val tileMapManager = remember {
        TileMapManager(
            initialTileMap = TileMap.fromString(
                """
                ################
                #...#.##.......#
                #.#.#.##.......#
                #..............#
                ######.#.......#
                ####...#.......#
                #......#.......#
                ################
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

    // First-pass monster: no MonsterAtlas is wired up yet (no sprite art exists), so it renders
    // via the flat placeholder-colour fallback in DungeonViewActor.drawSprite regardless of
    // renderMode. See docs/MonsterImplementationPlan.md.
    val monsters = remember {
        listOf(
            Monster(
                mob = Mob(initialCellX = 8, initialCellY = 3, initialFacing = Facing.WEST),
                currentAnimation = MonsterAnimation.single(tileIndex = 0),
            )
        )
    }
    // Monster is mutated by MonsterManager on the engine tick, not by Compose, so the minimap
    // needs its own snapshot state refreshed via a callback — same pattern as viewerSnapshot.
    var monsterMarkers by remember { mutableStateOf(monsters.toMinimapMarkers()) }
    val monsterManager = remember {
        MonsterManager(
            monsters = monsters,
            tileMapManager = tileMapManager,
            onMonstersChanged = { monsterMarkers = monsters.toMinimapMarkers() },
            isLoggingEnabled = true,
        )
    }
    val atlasImage = imageResource(Res.drawable.dungeon_atlas)
    val textMeasurer = rememberTextMeasurer()
    val dungeonRenderer = remember(atlasImage) {
        // Two caches — one per colour — so the same label text used for both a side wall
        // (cyan) and a front wall (yellow) never resolves to the wrong TextLayoutResult.
        val sideCache = mutableMapOf<String, TextLayoutResult>()
        val frontCache = mutableMapOf<String, TextLayoutResult>()
        DungeonRendererManager(
            fovWidth = 5,
            viewer = viewer,
            monsters = monsters,
//            renderMode = RenderMode.Wireframe(),
//            renderMode = RenderMode.Solid(),
            renderMode = RenderMode.Textured(
                atlas = DungeonAtlas(
                    image = atlasImage,
                    tileSize = 128,
                    cols = 2,
                ),
            ),
            debugLabelProvider = { text, isSideWall ->
                (if (isSideWall) sideCache else frontCache).getOrPut(text) {
                    textMeasurer.measure(
                        text,
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = if (isSideWall) Color.Cyan else Color.Yellow,
                        ),
                    )
                }
            },
            isLoggingEnabled = true
        )
    }
    val kubriko = remember {
        Kubriko.newInstance(
            tileMapManager,
            monsterManager,
            dungeonRenderer,
            playerManager,
            KeyboardInputManager.newInstance(),
            ViewportManager.newInstance(
                // Keeps the dungeon view's aspect ratio identical across desktop, Android, web,
                // and iOS regardless of window/screen shape — Kubriko letterboxes the rest itself
                // rather than us approximating it with a fillMaxSize(fraction) hack.
                aspectRatioMode = ViewportManager.AspectRatioMode.Fixed(
                    ratio = 3f / 2f,
                    width = 800f.sceneUnit,
                ),
            ),
            instanceNameForLogging = "DungeonCrawlerPoc",
        )
    }
    LaunchedEffect(Unit) {
        Logger.latestEntry.collect { entry ->
            println("[${entry.source}] ${entry.message}${entry.details?.let { " — $it" } ?: ""}")
        }
    }

    val frontWallCells by dungeonRenderer.frontWallCells.collectAsState()
    val sideWallCells by dungeonRenderer.sideWallCells.collectAsState()
    val visibleOpenCells by dungeonRenderer.visibleOpenCells.collectAsState()
    val pressedActions by playerManager.pressedActions.collectAsState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1208))
            .safeDrawingPadding()
            .padding(SCREEN_EDGE_PADDING),
    ) {
        val viewport: @Composable (Modifier) -> Unit = { viewportModifier ->
            Box(modifier = viewportModifier) {
                KubrikoViewport(
                    kubriko = kubriko,
                    modifier = Modifier
                        .padding(16.dp)
                        .border(2.dp, Color(0xFF8B6B52)),
                )
            }
        }
        val minimap: @Composable (Modifier) -> Unit = { minimapModifier ->
            MinimapOverlay(
                tileMap = tileMapManager.tileMap,
                cellX = viewerSnapshot.cellX,
                cellY = viewerSnapshot.cellY,
                facing = viewerSnapshot.facing,
                fovWidth = dungeonRenderer.fovWidth,
                viewDistance = dungeonRenderer.viewDistance,
                frontWallCells = frontWallCells,
                sideWallCells = sideWallCells,
                visibleOpenCells = visibleOpenCells,
                monsters = monsterMarkers,
                // Callers always give this the same weight/fillMax modifiers as the nav button
                // grid, so it always matches the grid's overall footprint exactly.
                modifier = minimapModifier,
            )
        }
        val navigationControls: @Composable (Modifier) -> Unit = { navModifier ->
            NavigationControls(
                onTurnLeft = playerManager::turnLeft,
                onMoveForward = playerManager::moveForward,
                onTurnRight = playerManager::turnRight,
                onStrafeLeft = playerManager::strafeLeft,
                onMoveBackward = playerManager::moveBackward,
                onStrafeRight = playerManager::strafeRight,
                pressedActions = pressedActions,
                modifier = navModifier,
            )
        }

        // Unlike the viewport (which keeps growing via its own weight(1f) + Kubriko's internal
        // AspectRatioMode.Fixed letterboxing to fill whatever space it's given), the minimap and
        // nav grid are capped at PANEL_MAX_SIZE — past that cap, extra window space just goes to
        // the viewport instead of the panel growing further.
        if (maxWidth > maxHeight) {
            // Landscape: minimap+grid stacked in a capped-width column beside the viewport, a
            // fixed gap between them, vertically centered next to the viewport.
            Row(modifier = Modifier.fillMaxSize()) {
                viewport(Modifier.weight(1f).fillMaxHeight().widthIn(min = VIEWPORT_MIN_SIZE))
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(start = PANEL_GAP),
                    contentAlignment = Alignment.Center,
                ) {
                    val panelSize = minOf(maxWidth, (maxHeight - PANEL_GAP) / 2)
                        .coerceIn(PANEL_MIN_SIZE, PANEL_MAX_SIZE)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        minimap(Modifier.size(panelSize))
                        Spacer(modifier = Modifier.height(PANEL_GAP))
                        navigationControls(Modifier.size(panelSize))
                    }
                }
            }
        } else {
            // Portrait: nav grid (bottom-left) and minimap (bottom-right) side by side below the
            // viewport, a fixed gap between them, the pair centered under the viewport.
            Column(modifier = Modifier.fillMaxSize()) {
                viewport(Modifier.weight(1f).fillMaxWidth().heightIn(min = VIEWPORT_MIN_SIZE))
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = PANEL_GAP),
                    contentAlignment = Alignment.Center,
                ) {
                    val panelSize = minOf((maxWidth - PANEL_GAP) / 2, maxHeight)
                        .coerceIn(PANEL_MIN_SIZE, PANEL_MAX_SIZE)
                    Row {
                        navigationControls(Modifier.size(panelSize))
                        Spacer(modifier = Modifier.width(PANEL_GAP))
                        minimap(Modifier.size(panelSize))
                    }
                }
            }
        }
    }
}

// Fixed padding between the viewport and the side panel, and between the minimap and the nav
// grid within it.
private val PANEL_GAP = 16.dp

// Upper bound on the minimap/nav-grid square — unlike the viewport, they don't grow to fill
// whatever screen space is available, they just stay centered next to it once this large.
private val PANEL_MAX_SIZE = NAV_GRID_SIZE

// Lower bound on that same square, and on the viewport's own weighted slot below — without these,
// an aggressively small window could squeeze either one down to zero.
private val PANEL_MIN_SIZE = 136.dp
private val VIEWPORT_MIN_SIZE = 160.dp

// Actual, non-scaling margin between the whole HUD and the screen edges — reserved before the
// HUD's own aspect ratio is fit to whatever space remains, so the button grid/minimap are never
// flush against the edge even when that edge is the one binding the aspect ratio.
private val SCREEN_EDGE_PADDING = 16.dp
