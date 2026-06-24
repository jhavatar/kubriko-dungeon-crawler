# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Kotlin Multiplatform + Compose Multiplatform proof-of-concept for a grid-based, first-person dungeon crawler (think legacy "blobber" RPGs), built on the [Kubriko](https://github.com/pandulapeter/kubriko) game engine. Targets: desktop (JVM) and Android.

## Commands

All commands run from the repo root via the wrapper.

```
./gradlew build                          # build + check everything (all modules, all targets)
./gradlew :app:run                       # run the desktop app
./gradlew :app:assembleDebug             # build the Android debug APK
./gradlew :app:installDebug              # install debug APK on a connected device/emulator (adb)
./gradlew :app:desktopMainClasses        # compile-check the desktop target only (fast)
./gradlew :app:compileDebugKotlinAndroid # compile-check the Android target only (fast)
./gradlew :app:lintDebug                 # Android Lint on the app module
```

There are no test source sets in any module yet (no `*Test` directories) — there is nothing to run with `test`/`desktopTest`/`testDebugUnitTest` tasks today.

Code style follows `kotlin.code.style=official` (set in `gradle.properties`); there is no separate lint/format tool (no ktlint/detekt) configured.

## Module structure

Three Gradle modules, each a Kotlin Multiplatform project with `androidTarget()` and `jvm("desktop")`:

- **`plugin-tilemap`** — pure grid/data model, no engine rendering dependency beyond the Kubriko `Manager` base class. Key types:
  - `TileMap`: stores walls per cell *edge* (only north/west walls are stored per cell; south/east are read by checking the neighboring cell's north/west wall), so adjacent cells share one source of truth for the wall between them. Map boundary is walled by default.
  - `GridPosition` / `GridActor`: discrete cell coordinates + `Facing`, kept separate from the engine's continuous `Positionable.body` so gameplay logic reasons in cells while rendering interpolates in scene units.
  - `Facing`: the 4 cardinal directions, with `opposite()`/`turnedLeft()`/`turnedRight()` helpers.
  - `TileMapManager`: a Kubriko `Manager` that owns the active `TileMap` and resolves move/turn legality against it (`canMoveTo`, `moveForward`, `moveBackward`).
- **`plugin-dungeon-crawler`** — rendering layer, depends on `plugin-tilemap`. `DungeonRendererManager` is a Kubriko `Manager` that, every frame (`onUpdate`), diffs the viewer's `GridPosition` against the last known one and projects changes into Kubriko `Actor`s via the injected `ActorManager` (managers inject each other with the `by manager<T>()` delegate). Currently it only spawns/removes a single placeholder `ForwardWallActor` (a flat colored box) when a wall is/isn't directly ahead — the real fixed-frustum slot-based wall/floor/ceiling projection is the next step, not yet built.
- **`app`** — the executable. `commonMain/App.kt` holds the shared `@Composable DungeonCrawlerApp()`, which wires up a `GridActor` (the viewer), a `TileMapManager`, and a `DungeonRendererManager` into one `Kubriko.newInstance(...)` engine instance, then renders it with `KubrikoViewport`. `desktopMain/Main.kt` and `androidMain/MainActivity.kt` are thin platform shells that just host that composable (a `Window` on desktop, a `ComponentActivity` on Android) — keep gameplay/engine-wiring changes in the shared composable, not the platform entry points.

There is currently **no input handling anywhere** — the viewer's grid position/facing is set once at creation and never changes. `TileMapManager.moveForward()`/`moveBackward()` and `GridActor`'s mutable fields exist and are ready to be driven by input, but nothing calls them yet.

## Kubriko engine notes

- Only the base `io.github.pandulapeter.kubriko:engine` artifact is used (declared once in `libs.versions.toml`, version `kubriko`). No input/audio/etc. Kubriko extension modules are pulled in.
- Gameplay/rendering code is organized as Kubriko `Manager`s (singleton-per-engine-instance services, e.g. `TileMapManager`, `DungeonRendererManager`) and `Actor`s (renderable/behavioral entities tracked by the engine's `ActorManager`, e.g. `ForwardWallActor`).
- `Kubriko.newInstance(...)` takes the set of managers for one engine instance; `KubrikoViewport(kubriko = ...)` is the Compose entry point that renders it.

## Build setup gotchas

These are non-obvious and cost real trial-and-error to work out — read before touching Gradle/AGP config:

- **AGP must stay on the 8.x line (pinned to `8.13.2` in `libs.versions.toml`).** AGP 9.0+ refuses to apply `com.android.application`/`com.android.library` in a module that also applies `org.jetbrains.kotlin.multiplatform` ("not compatible... since AGP 9.0"); the AGP-recommended fix is migrating to the separate `com.android.kotlin.multiplatform.library` plugin, which is a bigger structural change this project hasn't made.
- **The root `build.gradle.kts` must declare the Android plugins with `apply false`** alongside the other plugins. Without that, applying `com.android.application`/`com.android.library` only in `app`/`plugin-*` fails with `Can't infer current AndroidGradlePluginVersion: Is the Android plugin applied?` — the Kotlin Gradle plugin needs AGP registered at the root to detect its version.
- **`android.useAndroidX=true` is required** in `gradle.properties` once any AndroidX dependency (e.g. `activity-compose`) is on the classpath, or the build fails with a `kmpPartiallyResolvedDependenciesChecker` error.
- **`minSdk` is pinned to 29** because `io.github.pandulapeter.kubriko:engine-android` declares `minSdk 29` in its own manifest; the manifest merger hard-fails if the app's `minSdk` is lower.
- **`.idea/workspace.xml`'s `autoReloadType` should be `ALL`.** If it's `NONE`, Android Studio won't pick up Gradle file changes made outside the IDE (e.g. by Claude Code or other terminal edits) until a manual sync — which can make it look like Android Studio is "missing" a target/run configuration that actually exists on disk.
