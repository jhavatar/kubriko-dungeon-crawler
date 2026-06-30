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

## Dungeon renderer features

All features live in `plugin-dungeon-crawler`.

### Render modes

- **Wireframe** — hairline outlines only; useful for debugging geometry.
- **Solid** — flat-colour surfaces tinted by the active `DungeonColorTheme`.
- **Textured** — perspective-correct tile atlas sampling for walls, floors, and ceilings via a 4×4 projective homography.

### Perspective rendering

- **Front walls** — each exposed cell face is projected as a vertical strip scaled by distance.
- **Side walls** — rendered as perspective trapezoids. The visible tile U slice is computed correctly even for walls that extend behind the player (the cell immediately beside them), where only a narrow fraction of the tile face is on screen.
- **Floor and ceiling** — rendered as perspective quads. The near depth band (the cell immediately around the player) uses a partial tile V slice matching the physically visible portion.

### Angular occlusion culling

A front-to-back `AngularOcclusionBuffer` tracks covered angular intervals. Surfaces fully occluded by closer walls are skipped, keeping draw-call count proportional to visible geometry.

### Brightness / lighting

- Exponential torch falloff: `brightness = exp(−k × Euclidean distance)`, clamped to `[MIN_BRIGHTNESS, 1.0]` so surfaces never blow out or go completely black.
- Side walls are additionally multiplied by `DungeonColorTheme.sideWallShadow` to darken them relative to front-facing surfaces.
- In Textured mode, brightness is applied as a grayscale `ColorFilter.Multiply` on walls (fully opaque) and as a `BlendMode.Multiply` path fill on floor/ceiling bands, with independent near and far brightness values per band for a seamless depth gradient.

### Theming

- **`DungeonColorTheme`** — depth-gradient colour palette for front walls, side walls, floor, and ceiling. Torch-lit stone defaults; pass a custom instance to re-theme.
- **`DungeonAtlas`** — uniform tile atlas descriptor: tile size, column count, and per-surface tile indices (`frontWallTile`, `sideWallTile`, `floorTile`, `ceilTile`). Canonical layout is wall=0, floor=1, ceiling=2. Indices are validated at construction.

### Configurability

| Parameter | Default | Effect |
|---|---|---|
| `fovWidth` | 5 | Horizontal field of view in cell-slot units |
| `viewDistance` | 4 | Maximum render depth in cells |
| `wallHeightScale` | 0.8 | Fraction of viewport height a wall at depth 1 occupies |

### Debug overlay

Pass a `debugLabelProvider` lambda to render per-strip coordinate labels on top of geometry (cyan for side walls, yellow for front walls). Leave `null` in non-debug builds.

---

## Project structure

- `plugin-tilemap` — grid/tile-map data model (`TileMap`, `GridPosition`, `Facing`) and the manager that resolves move/turn legality (`TileMapManager`).
- `plugin-dungeon-crawler` — first-person renderer. `DungeonRendererManager` projects the viewer's grid position into draw commands (walls, floors, ceilings) using a fixed-frustum slot-based algorithm with an angular occlusion buffer. `DungeonViewActor` executes those commands each frame via Compose `DrawScope`. Supports `RenderMode.Wireframe`, `Solid`, and `Textured`; `DungeonAtlas` and `DungeonColorTheme` control textures and colour palette.
- `app` — the executable; wires the above into a Kubriko engine instance, adds `PlayerManager` for keyboard input, and hosts it on each platform.

See `CLAUDE.md` for more detail on the architecture and known Gradle/AGP configuration quirks.
