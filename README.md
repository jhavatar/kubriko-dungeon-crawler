# Kubriko Dungeon Crawler

A proof-of-concept grid-based, first-person dungeon crawler (in the spirit of classic "blobber" RPGs), built with [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html), [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/), and the [Kubriko](https://github.com/pandulapeter/kubriko) game engine.

## Status

Active POC. A first-person dungeon view is fully rendered from a tile map with perspective-correct walls, floors, and ceilings. Three render modes are supported: wireframe, solid colour, and textured (via a tile atlas). Keyboard input moves and turns the player in real time. A minimap overlay shows the tile map, viewer position, and current frustum.

Controls (desktop/web): **W/S** move forward/back, **A/D** strafe, **Q/E** turn left/right.

## Targets

- **Desktop** (JVM)
- **Android** (minSdk 29)
- **Web** (Wasm/wasmJs)
- **iOS** (`iosArm64` / `iosSimulatorArm64`) — framework builds; no Xcode host project yet

## Prerequisites

- JDK 21
- For Android: an installed Android SDK (`local.properties` must point `sdk.dir` at it) and, to install/run, a connected device or emulator

## Building and running

From the repo root:

```
./gradlew build                            # build everything
./gradlew :app:run                         # run the desktop app
./gradlew :app:assembleDebug               # build the Android debug APK
./gradlew :app:installDebug                # install the debug APK on a connected device/emulator
./gradlew :app:wasmJsBrowserDevelopmentRun # run the web build with dev server + auto-reload
```

## Project structure

- `plugin-tilemap` — grid/tile-map data model (`TileMap`, `GridPosition`, `Facing`) and the manager that resolves move/turn legality (`TileMapManager`).
- `plugin-dungeon-crawler` — first-person renderer. `DungeonRendererManager` projects the viewer's grid position into draw commands (walls, floors, ceilings) using a fixed-frustum slot-based algorithm with an angular occlusion buffer. `DungeonViewActor` executes those commands each frame via Compose `DrawScope`. Supports `RenderMode.WIREFRAME`, `SOLID`, and `TEXTURED`; `DungeonAtlas` and `DungeonColorTheme` control textures and colour palette.
- `app` — the executable; wires the above into a Kubriko engine instance, adds `PlayerManager` for keyboard input, and hosts it on each platform.

See `CLAUDE.md` for more detail on the architecture and known Gradle/AGP configuration quirks.
