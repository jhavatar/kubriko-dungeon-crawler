# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Kotlin Multiplatform + Compose Multiplatform proof-of-concept for a grid-based, first-person dungeon crawler (think legacy "blobber" RPGs), built on the [Kubriko](https://github.com/pandulapeter/kubriko) game engine. Targets: desktop (JVM), Android, web (Wasm via `wasmJs`), and iOS (`iosArm64`/`iosSimulatorArm64`).

## Commands

All commands run from the repo root via the wrapper.

```
./gradlew build                              # build + check everything (all modules, all targets)
./gradlew :app:run                           # run the desktop app
./gradlew :app:assembleDebug                 # build the Android debug APK
./gradlew :app:installDebug                  # install debug APK on a connected device/emulator (adb)
./gradlew :app:desktopMainClasses            # compile-check the desktop target only (fast)
./gradlew :app:compileDebugKotlinAndroid     # compile-check the Android target only (fast)
./gradlew :app:lintDebug                     # Android Lint on the app module
./gradlew :app:wasmJsBrowserDevelopmentRun   # run the web build with a dev server + auto-reload
./gradlew :app:wasmJsBrowserDistribution     # produce the static web bundle in app/build/dist/wasmJs/productionExecutable
./gradlew :app:linkDebugFrameworkIosArm64    # build the iOS device .framework (compile-check; no Xcode host project exists yet, see below)
```

There are no test source sets in any module yet (no `*Test` directories) — there is nothing to run with `test`/`desktopTest`/`testDebugUnitTest` tasks today.

Code style follows `kotlin.code.style=official` (set in `gradle.properties`); there is no separate lint/format tool (no ktlint/detekt) configured.

## Module structure

Three Gradle modules, each a Kotlin Multiplatform project targeting `androidTarget()`, `jvm("desktop")`, `wasmJs()`, `iosArm64()`, and `iosSimulatorArm64()` — exactly the platforms `io.github.pandulapeter.kubriko:engine` itself publishes (no plain `js()`, no `iosX64`, since the engine doesn't publish those variants).

- **`plugin-tilemap`** — pure grid/data model, no engine rendering dependency beyond the Kubriko `Manager` base class. Key types:
  - `CellType`: enum `OPEN` / `WALL` / `SPECIAL` (doors, pits, stairs etc.) per "worm tunnel" conventions — a wall occupies a full cell, not an edge.
  - `TileMap`: stores a `CellType` per cell. Outer ring is always `WALL`. `cellTypeAt(x, y)` returns `WALL` for out-of-bounds coordinates. `setCellType` can change interior cells.
  - `GridPosition`: interface for discrete cell coordinates + `Facing`, kept separate from the engine's continuous `Positionable.body` so gameplay logic reasons in cells while rendering interpolates in scene units.
  - `Mob`: the concrete `GridPosition` implementation for anything that occupies a single cell and can move — the player party or a monster. ("Actor" was avoided since that's a Kubriko term.)
  - `Facing`: the 4 cardinal directions, with `opposite()`/`turnedLeft()`/`turnedRight()` helpers.
  - `TileMapManager`: a Kubriko `Manager` that owns the active `TileMap` and resolves move/turn legality against it. Party movement: `moveForward`, `moveBackward`, `strafeLeft`, `strafeRight`. Monster movement: `moveForward`/`moveBackward` only (strafe is available in the manager but not called for monsters by convention).
- **`plugin-dungeon-crawler`** — rendering layer, depends on `plugin-tilemap`. Key types:
  - `DungeonRendererManager`: a Kubriko `Manager` that, on every position/viewport change, runs a fixed-frustum slot-based projection algorithm. It iterates depth bands D=1…viewDistance and lateral slots lat=-latMax…latMax, maintaining an `AngularOcclusionBuffer` for front-to-back occlusion culling. It emits `DrawCommand` values (front walls, side-wall trapezoids, floor/ceiling bands) into a buffer consumed by `DungeonViewActor`.
  - `DungeonViewActor`: a Kubriko `Actor` that executes the draw-command buffer each frame in Compose `DrawScope`. Handles three modes via `RenderMode` — `Wireframe` (hairlines), `Solid` (flat colour), and `Textured` (perspective-correct tile atlas sampling via a 4×4 projective matrix).
  - `DungeonAtlas`: descriptor for a uniform tile atlas image — tile size, column count, and per-surface tile indices (canonical order: wall=0, floor=1, ceiling=2). `init` validates that all indices are in range.
  - `DungeonColorTheme`: depth-gradient colour palette for walls (front/side), floor, and ceiling. All parameters have torch-lit stone defaults; pass a custom instance to re-theme.
  - `AngularOcclusionBuffer`: tracks which angular sub-intervals of the view frustum are already covered by nearer walls, so farther surfaces are only drawn where still visible.
  - `DrawCommand`: sealed class — `FrontStrip` (front wall sub-interval), `SideStrip` (side-wall trapezoid; `tileUNearFraction` encodes the tile U slice hidden behind the screen edge at the near end — always >0 for sideDepth=0 walls where the wall extends behind the player), `FloorCeilingBand` (floor + ceiling pair with perspective quad corners for texture mapping; `vNearFraction` controls the tile V slice shown in the near depth band).
- **`app`** — the executable. `commonMain/App.kt` holds the shared `@Composable DungeonCrawlerApp()`, which wires up a `Mob` (the viewer), a `TileMapManager`, a `DungeonRendererManager`, and a `PlayerManager` into one `Kubriko.newInstance(...)` engine instance, then renders it with `KubrikoViewport`. A `MinimapOverlay` composable renders the tile map, viewer position, and frustum in a corner overlay. `desktopMain/Main.kt`, `androidMain/MainActivity.kt`, and `wasmJsMain/Main.kt` are thin platform shells that just host that composable — keep gameplay/engine-wiring changes in the shared composable, not the platform entry points. `iosMain/MainViewController.kt` exposes a `MainViewController(): UIViewController` (via `ComposeUIViewController`) for the same purpose, but **there is no Xcode project in this repo to host it yet** — `./gradlew :app:linkDebugFrameworkIosArm64`/`linkDebugFrameworkIosSimulatorArm64` produce the `DungeonCrawlerApp.framework`, but actually running on a simulator/device requires creating an Xcode project that embeds it and calls `MainViewController()`, which hasn't been done.
  - `PlayerManager`: a Kubriko `Manager` that implements `KeyboardInputAware`. Maps **W/S** → move forward/back, **A/D** → strafe left/right, **Q/E** → turn left/right. Each keypress delegates to `TileMapManager` (which validates legality against the tile map) and notifies `DungeonCrawlerApp` via a callback to refresh the minimap state.

## Kubriko engine notes

- Only the base `io.github.pandulapeter.kubriko:engine` artifact is used (declared once in `libs.versions.toml`, version `kubriko`). No input/audio/etc. Kubriko extension modules are pulled in.
- Gameplay/rendering code is organized as Kubriko `Manager`s (singleton-per-engine-instance services, e.g. `TileMapManager`, `DungeonRendererManager`) and `Actor`s (renderable/behavioral entities tracked by the engine's `ActorManager`, e.g. `DungeonViewActor`).
- `Kubriko.newInstance(...)` takes the set of managers for one engine instance; `KubrikoViewport(kubriko = ...)` is the Compose entry point that renders it.

## Build setup gotchas

These are non-obvious and cost real trial-and-error to work out — read before touching Gradle/AGP config:

- **AGP must stay on the 8.x line (pinned to `8.13.2` in `libs.versions.toml`).** AGP 9.0+ refuses to apply `com.android.application`/`com.android.library` in a module that also applies `org.jetbrains.kotlin.multiplatform` ("not compatible... since AGP 9.0"); the AGP-recommended fix is migrating to the separate `com.android.kotlin.multiplatform.library` plugin, which is a bigger structural change this project hasn't made.
- **The root `build.gradle.kts` must declare the Android plugins with `apply false`** alongside the other plugins. Without that, applying `com.android.application`/`com.android.library` only in `app`/`plugin-*` fails with `Can't infer current AndroidGradlePluginVersion: Is the Android plugin applied?` — the Kotlin Gradle plugin needs AGP registered at the root to detect its version.
- **`android.useAndroidX=true` is required** in `gradle.properties` once any AndroidX dependency (e.g. `activity-compose`) is on the classpath, or the build fails with a `kmpPartiallyResolvedDependenciesChecker` error.
- **`minSdk` is pinned to 29** because `io.github.pandulapeter.kubriko:engine-android` declares `minSdk 29` in its own manifest; the manifest merger hard-fails if the app's `minSdk` is lower.
- **`.idea/workspace.xml`'s `autoReloadType` should be `ALL`.** If it's `NONE`, Android Studio won't pick up Gradle file changes made outside the IDE (e.g. by Claude Code or other terminal edits) until a manual sync — which can make it look like Android Studio is "missing" a target/run configuration that actually exists on disk.
- **`wasmJs { ... }` requires `@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)`** in every module's `build.gradle.kts` that declares the target — Kotlin 2.4.0 still gates the whole `wasmJs` Gradle DSL behind this opt-in.
- **`ComposeViewport` (the web entry point, in `app/src/wasmJsMain/kotlin/.../Main.kt`) requires `@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)`** in Compose Multiplatform 1.11.1, or the build fails with "This API is experimental."
- **`kotlin.native.ignoreDisabledTargets=true` is set in `gradle.properties`** because this is an Intel (`x86_64`) Mac: Kotlin/Native can still cross-compile/link `iosSimulatorArm64` *binaries* from this host, but can't *run* `iosSimulatorArm64Test`, which would otherwise print a warning on every build. (Kotlin/Native also prints a one-time "Deprecated Kotlin/Native Host" warning for `macos_x64` itself — that's just a heads-up that Kotlin/Native support for Intel Macs is being phased out, not an error.)
- **The web build's output filename is pinned via `browser { commonWebpackConfig { outputFileName = "dungeoncrawler.js" } }`** in `app/build.gradle.kts`, and `wasmJsMain/resources/index.html`'s `<script src="dungeoncrawler.js">` must match it — the default webpack output name is derived from the Gradle module path and is less predictable.

## Development docs

`docs/` contains living reference documents — keep them up to date when changing the systems they describe:

- **`docs/VisualRenderingChecklist.md`** — run after any change to `DungeonRendererManager`, `DungeonViewActor`, or `DrawCommand`. Covers SW-1/SW-2 (side-wall tile fractions), FW-1/FW-2 (front-wall coverage and brightness), FC-1/FC-2/FC-3 (floor/ceiling continuity and near-band proportions), and CM-1/CM-2 (cross-mode sanity). Past rendering bugs were caught late because no visual verification step existed; this checklist exists to prevent that.

## Known deferred work

- **Floor/ceiling GPU over-submission:** `drawFloorCeilingTextured` submits the full atlas image to the GPU for every `FloorCeilingBand` command (~60 submissions per frame with default settings). Acceptable for the PoC but will degrade with larger atlases or wider view distances. Longer-term fix: pre-warp visible tiles into an off-screen layer, or switch to a triangle-mesh + shader approach that samples only the relevant tile region.
