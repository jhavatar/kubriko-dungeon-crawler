package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.Color

// Colour palette for the dungeon view. All fields have physically-motivated defaults:
// warm torch-stone walls, dim earth floor, cooler stone ceiling. Pass a custom instance
// to RenderMode.Solid / RenderMode.Textured to re-theme the dungeon without touching renderer code.
data class DungeonColorTheme(
    // Front wall torchlight gradient: dark base colour (brightness=0) + bright range at brightness=1.
    // brightness=1 → (0.75, 0.50, 0.32) warm amber; brightness=0.15 → (0.33, 0.19, 0.12) dark stone.
    val wallR: Float = 0.25f, val wallRRange: Float = 0.50f,
    val wallG: Float = 0.14f, val wallGRange: Float = 0.36f,
    val wallB: Float = 0.08f, val wallBRange: Float = 0.24f,
    // Side walls face away from the torch; multiply their colour by this shadow factor.
    val sideWallShadow: Float = 0.65f,
    // Floor gradient (cool-neutral flagstone — grey with slight blue cast to contrast warm walls).
    // brightness=1 → (0.38, 0.36, 0.38) medium grey; brightness=0.15 → (0.17, 0.16, 0.18) dark grey.
    val floorR: Float = 0.13f, val floorRRange: Float = 0.25f,
    val floorG: Float = 0.13f, val floorGRange: Float = 0.23f,
    val floorB: Float = 0.14f, val floorBRange: Float = 0.24f,
    // Ceiling gradient (vaulted stone — blue-grey, torchlight barely reaches here).
    // brightness=1 → (0.22, 0.26, 0.40) blue-grey; brightness=0.15 → (0.10, 0.12, 0.21) dark blue.
    val ceilR: Float = 0.08f, val ceilRRange: Float = 0.14f,
    val ceilG: Float = 0.10f, val ceilGRange: Float = 0.16f,
    val ceilB: Float = 0.18f, val ceilBRange: Float = 0.22f,
)

// brightness = 0..1 supplied by the renderer (e.g. torchBrightness(hypot(depth, lat))).
// Each function maps that scalar to a colour by interpolating from the dark base to the full range.

internal fun DungeonColorTheme.frontWallColor(brightness: Float): Color =
    Color(wallR + wallRRange * brightness, wallG + wallGRange * brightness, wallB + wallBRange * brightness)

internal fun DungeonColorTheme.sideWallColor(brightness: Float): Color =
    Color(
        (wallR + wallRRange * brightness) * sideWallShadow,
        (wallG + wallGRange * brightness) * sideWallShadow,
        (wallB + wallBRange * brightness) * sideWallShadow,
    )

internal fun DungeonColorTheme.floorColor(brightness: Float): Color =
    Color(floorR + floorRRange * brightness, floorG + floorGRange * brightness, floorB + floorBRange * brightness)

internal fun DungeonColorTheme.ceilColor(brightness: Float): Color =
    Color(ceilR + ceilRRange * brightness, ceilG + ceilGRange * brightness, ceilB + ceilBRange * brightness)
