package com.chthonic.dungeoncrawler.tilemap

/**
 * Discrete grid-space placement, kept separate from the engine's continuous
 * `Positionable.body` so gameplay logic can reason in cells while rendering still
 * interpolates through scene units.
 */
interface GridPosition {
    var cellX: Int
    var cellY: Int
    var facing: Facing
}
