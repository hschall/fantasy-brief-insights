package com.aviato.fantasybrief.data

/**
 * The MECHANICAL half of the Power Index.
 *
 * V and two of the three CERTAINTY factors can be derived from data the app
 * already holds. The third — trigger status — asks whether an opportunity is
 * real, which requires news and cannot be automated. So this emits components
 * and deliberately refuses to produce a composite: a number that silently
 * assumed a trigger would be worse than no number at all.
 *
 * PI = V x (trigger x roleCapture x floor), capped at 95. Nothing is ever 100.
 */
data class PowerInputs(
    val valueBand: Int,          // 0-100, against THIS league's replacement level
    val roleCapture: Double,     // from depth rank
    val floor: Double,           // startable now, or pure contingency
    val roleBasis: String,       // why roleCapture is what it is
    val ceilingIfConfirmed: Int  // PI assuming trigger = 1.0. The upper bound.
) {
    /** Never a bare index — always shows what it is made of. */
    fun render(): String =
        "V$valueBand x role ${pct(roleCapture)} x floor ${pct(floor)} " +
            "=> ceiling PI $ceilingIfConfirmed ($roleBasis; multiply by trigger)"

    private fun pct(v: Double) = "${(v * 100).toInt()}%"
}

object PowerIndex {

    const val RUBRIC_VERSION = "POWER INDEX RUBRIC v1.0"

    fun forPlayer(
        projection: Double?,
        position: String,
        replacement: ReplacementLevel,
        depthRank: Int?,
        depthCount: Int?,
        isStartableNow: Boolean
    ): PowerInputs {
        val level = replacement.level(position)
        val proj = projection ?: 0.0

        // VALUE against this league, never absolute numbers. A 14.0 is a poor
        // quarterback and an excellent tight end.
        val valueBand = when {
            level == null || level.eliteLine <= 0.0 -> 30
            proj >= level.eliteLine * 1.25 -> 90
            proj >= level.eliteLine -> 75
            proj >= level.solidLine -> 55
            proj >= level.solidLine * 0.7 -> 30
            else -> 15
        }

        // ROLE CAPTURE, now a lookup rather than a judgment call.
        val (roleCapture, basis) = when {
            depthRank == null -> 0.5 to "no depth chart data"
            depthRank == 1 && (depthCount ?: 9) <= 2 -> 1.0 to "depth rank 1, no competition"
            depthRank == 1 -> 0.7 to "depth rank 1 in a committee"
            depthRank == 2 -> 0.45 to "depth rank 2"
            else -> 0.2 to "depth rank $depthRank"
        }

        // STANDALONE FLOOR: is there value if nothing changes?
        val floor = when {
            isStartableNow -> 1.0
            valueBand >= 40 -> 0.8
            else -> 0.6
        }

        val ceiling = ((valueBand * roleCapture * floor).toInt() / 5) * 5
        return PowerInputs(valueBand, roleCapture, floor, basis, ceiling)
    }
}
