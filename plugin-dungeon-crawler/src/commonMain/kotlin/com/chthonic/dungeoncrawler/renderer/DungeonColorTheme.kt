package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.Color

// Colour palette for the dungeon view. All fields have physically-motivated defaults:
// warm torch-stone walls, dim earth floor, cooler stone ceiling. Pass a custom instance
// to DungeonRendererManager.colorTheme to re-theme the dungeon without touching renderer code.
data class DungeonColorTheme(
    // Front wall torchlight gradient: dark base colour (far, t=0) + bright range added near (t=1).
    val wallR: Float = 0.18f, val wallRRange: Float = 0.36f,
    val wallG: Float = 0.10f, val wallGRange: Float = 0.27f,
    val wallB: Float = 0.05f, val wallBRange: Float = 0.17f,
    // Side walls face away from the torch; multiply their colour by this shadow factor.
    val sideWallShadow: Float = 0.65f,
    // Floor gradient (warm dark earth).
    val floorR: Float = 0.08f, val floorRRange: Float = 0.10f,
    val floorG: Float = 0.05f, val floorGRange: Float = 0.07f,
    val floorB: Float = 0.02f, val floorBRange: Float = 0.03f,
    // Ceiling gradient (cooler stone vault, less direct torchlight).
    val ceilR: Float = 0.05f, val ceilRRange: Float = 0.06f,
    val ceilG: Float = 0.03f, val ceilGRange: Float = 0.04f,
    val ceilB: Float = 0.01f, val ceilBRange: Float = 0.03f,
)

// t = 1 at depth 1 (nearest / brightest), t = 0 at depth viewDistance (dimmest).
internal fun DungeonColorTheme.frontWallColor(depth: Int, viewDistance: Int): Color {
    val t = 1f - (depth - 1f) / (viewDistance - 1f).coerceAtLeast(1f)
    return Color(wallR + wallRRange * t, wallG + wallGRange * t, wallB + wallBRange * t)
}

internal fun DungeonColorTheme.sideWallColor(depth: Int, viewDistance: Int): Color {
    val t = 1f - depth.toFloat() / viewDistance.toFloat()
    return Color(
        (wallR + wallRRange * t) * sideWallShadow,
        (wallG + wallGRange * t) * sideWallShadow,
        (wallB + wallBRange * t) * sideWallShadow,
    )
}

internal fun DungeonColorTheme.floorColor(depth: Int, viewDistance: Int): Color {
    val t = 1f - depth.toFloat() / (viewDistance + 1f)
    return Color(floorR + floorRRange * t, floorG + floorGRange * t, floorB + floorBRange * t)
}

internal fun DungeonColorTheme.ceilColor(depth: Int, viewDistance: Int): Color {
    val t = 1f - depth.toFloat() / (viewDistance + 1f)
    return Color(ceilR + ceilRRange * t, ceilG + ceilGRange * t, ceilB + ceilBRange * t)
}
