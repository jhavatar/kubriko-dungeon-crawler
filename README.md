# Kubriko Dungeon Crawler

A proof-of-concept grid-based, first-person dungeon crawler (in the spirit of classic "blobber" RPGs), built with [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html), [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/), and the [Kubriko](https://github.com/pandulapeter/kubriko) game engine.

## Status

Early POC. The viewer can be placed in a tile-mapped dungeon and a wall directly ahead is rendered as a placeholder colored box, but there is no input handling yet — the viewer's position never changes after startup.

## Targets

- **Desktop** (JVM)
- **Android** (minSdk 29)

## Prerequisites

- JDK 21
- For Android: an installed Android SDK (`local.properties` must point `sdk.dir` at it) and, to install/run, a connected device or emulator

## Building and running

From the repo root:

```
./gradlew build              # build everything
./gradlew :app:run           # run the desktop app
./gradlew :app:assembleDebug # build the Android debug APK
./gradlew :app:installDebug  # install the debug APK on a connected device/emulator
```

## Project structure

- `plugin-tilemap` — grid/tile-map data model (`TileMap`, `GridPosition`, `Facing`) and the manager that resolves move/turn legality (`TileMapManager`).
- `plugin-dungeon-crawler` — projects the viewer's grid position into renderable Kubriko actors (`DungeonRendererManager`).
- `app` — the executable; wires the above into a Kubriko engine instance and hosts it on each platform (desktop window / Android activity).

See `CLAUDE.md` for more detail on the architecture and known Gradle/AGP configuration quirks.
