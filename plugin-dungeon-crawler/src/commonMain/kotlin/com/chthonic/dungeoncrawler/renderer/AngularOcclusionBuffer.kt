package com.chthonic.dungeoncrawler.renderer

// A slope-angle interval in the view frustum (angle = lateral / depth).
internal data class Interval(val lo: Float, val hi: Float)

// Tracks which angular sub-intervals of the view frustum are already covered by solid walls
// encountered during front-to-back traversal — equivalent to DOOM's solidsegs mechanism.
//
// The buffer maintains a sorted, non-overlapping list of covered Intervals. Call clear() at the
// start of each frame traversal. subtract() queries visibility; merge() registers a solid surface.
//
// frustumLo/Hi define the full angular extent of the view frustum. isFull is maintained
// incrementally in O(1) at the end of each merge() call: because merge() always combines
// touching intervals, full frustum coverage is always represented by a single entry, so the
// check reduces to covered.size == 1 && covered[0] spans [frustumLo, frustumHi].
internal class AngularOcclusionBuffer(private val frustumLo: Float, private val frustumHi: Float) {
    private val covered = mutableListOf<Interval>()
    private val scratch  = mutableListOf<Interval>()

    // True once the union of covered entries spans the entire frustum with no gaps.
    // Updated in O(1) after each merge(); read at the end of each depth level to short-circuit
    // the traversal as soon as no further geometry can be visible.
    var isFull: Boolean = false
        private set

    fun clear() { covered.clear(); isFull = false }

    // Returns the sub-intervals of [interval] not yet covered. An empty result means the
    // interval is fully occluded. Non-empty sub-intervals clip wall geometry to visible portions.
    fun subtract(interval: Interval): List<Interval> {
        var remaining = listOf(interval)
        for ((covL, covR) in covered) {
            remaining = remaining.flatMap { (a, b) ->
                buildList {
                    if (a < covL) add(Interval(a, minOf(b, covL)))
                    if (b > covR) add(Interval(maxOf(a, covR), b))
                }
            }
            if (remaining.isEmpty()) break
        }
        return remaining
    }

    // Inserts [interval] into the buffer, merging overlapping or touching entries so the list
    // stays sorted and non-overlapping. Uses an internal scratch list to avoid per-call allocation.
    fun merge(interval: Interval) {
        var (l, r) = interval
        scratch.clear()
        var inserted = false
        for ((covL, covR) in covered) {
            when {
                covR < l -> scratch.add(Interval(covL, covR))
                covL > r -> {
                    if (!inserted) { scratch.add(Interval(l, r)); inserted = true }
                    scratch.add(Interval(covL, covR))
                }
                else -> { l = minOf(l, covL); r = maxOf(r, covR) }
            }
        }
        if (!inserted) scratch.add(Interval(l, r))
        covered.clear()
        covered.addAll(scratch)
        isFull = covered.size == 1 && covered[0].lo <= frustumLo && covered[0].hi >= frustumHi
    }
}
