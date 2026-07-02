package com.chthonic.dungeoncrawler.renderer

import androidx.compose.ui.graphics.ImageBitmap

// Texture atlas for monster sprites. First-pass shape: a uniform tile grid, structurally
// identical to DungeonAtlas (tileSize x cols) — a separate type because monster tile indices
// are looked up per (Monster, MonsterViewDirection, frame) rather than DungeonAtlas's fixed
// wall/floor/ceiling roles, and there's no shared "canonical tile order" to validate against.
//
// docs/MonsterImplementationPlan.md "Monster atlas format" recommends a packed-region manifest
// (variable-size regions + a name->rect map) as the typical long-term format for sprites of
// differing sizes; this uniform grid is a simpler stand-in that's enough for a first pass.
data class MonsterAtlas(
    val image: ImageBitmap,
    val tileSize: Int,
    val cols: Int,
)
