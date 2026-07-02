package com.chthonic.dungeoncrawler.renderer

// Which side of a monster's own body the viewer is currently looking at — body-relative
// (like a character sprite sheet), not screen-relative. See DungeonRendererManager's
// viewDirectionOf and docs/MonsterImplementationPlan.md "Facing-dependent sprites".
enum class MonsterViewDirection { FRONT, BACK, LEFT, RIGHT }

// One animation's frame sequence (tile indices into a MonsterAtlas), one list per relative
// viewing direction.
class MonsterAnimation(
    val front: List<Int>,
    val back: List<Int>,
    val left: List<Int>,
    val right: List<Int>,
) {
    fun framesFor(direction: MonsterViewDirection): List<Int> = when (direction) {
        MonsterViewDirection.FRONT -> front
        MonsterViewDirection.BACK -> back
        MonsterViewDirection.LEFT -> left
        MonsterViewDirection.RIGHT -> right
    }

    companion object {
        // First-pass convenience: the same single tile from every direction, until real
        // directional art exists (docs/MonsterImplementationPlan.md "Deferred / out of scope").
        fun single(tileIndex: Int): MonsterAnimation {
            val frames = listOf(tileIndex)
            return MonsterAnimation(front = frames, back = frames, left = frames, right = frames)
        }
    }
}
