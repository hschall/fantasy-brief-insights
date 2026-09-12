package com.aviato.fantasybrief.data

/**
 * What a startable player looks like IN THIS LEAGUE.
 *
 * A projection of 14.0 is a bad QB and an excellent TE. Absolute numbers
 * are meaningless; the only useful reference is what the other nine
 * managers are actually starting. Computed from live rosters, so it
 * re-derives itself for any league size or lineup shape.
 */
data class PositionLevel(
    val position: String,
    val eliteLine: Double,    // median starter — clearing this improves a lineup
    val solidLine: Double,    // 25th percentile starter — bench-worthy line
    val worstStarter: Double, // actual minimum, for reference only
    val sampleSize: Int
)

class ReplacementLevel(private val levels: Map<String, PositionLevel>) {

    fun elite(position: String): Double? = levels[position]?.eliteLine
    fun solid(position: String): Double? = levels[position]?.solidLine
    fun level(position: String): PositionLevel? = levels[position]
    val positions: List<PositionLevel> get() = levels.values.sortedBy { it.position }

    /** ELITE / SOLID / BELOW, or null when the position has no sample. */
    fun rank(position: String, projection: Double?): String? {
        val level = levels[position] ?: return null
        val p = projection ?: return null
        return when {
            p >= level.eliteLine -> "ELITE"
            p >= level.solidLine -> "SOLID"
            else -> "BELOW"
        }
    }

    companion object {
        /**
         * Uses STARTERS only. Bench players are what managers chose not to
         * start, so including them would drag the line below what actually
         * competes each week. Flex counts toward its player's real position.
         */
        fun from(league: League): ReplacementLevel {
            // Zero projections mean "ESPN hasn't published", not "bad" — so
            // they're excluded. But if EVERY starter at a position is zero
            // (D/ST in some leagues), dropping the position entirely leaves
            // those players with no frame at all. Keep the position with a
            // zeroed line and let the sample size say why.
            val byPosition = mutableMapOf<String, MutableList<Double>>()
            val allStarters = mutableMapOf<String, Int>()
            league.teams.forEach { team ->
                team.roster.filter { it.isStarter }.forEach { p ->
                    allStarters[p.position] = (allStarters[p.position] ?: 0) + 1
                    val proj = p.projection ?: return@forEach
                    if (proj <= 0.0) return@forEach
                    byPosition.getOrPut(p.position) { mutableListOf() }.add(proj)
                }
            }
            allStarters.forEach { (position, count) ->
                if (byPosition[position] == null && count >= 3) {
                    byPosition[position] = mutableListOf()
                }
            }

            val levels = byPosition.mapNotNull { (position, values) ->
                if (values.isEmpty()) {
                    // No published projections anywhere at this position.
                    return@mapNotNull position to
                        PositionLevel(position, 0.0, 0.0, 0.0, 0)
                }
                if (values.size < 3) return@mapNotNull null
                val sorted = values.sorted()
                val median = if (sorted.size % 2 == 0)
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
                else sorted[sorted.size / 2]

                // 25th percentile, NOT the minimum. One manager starting a
                // replacement-level player drags the floor for the whole
                // league — the RB minimum was 8.8, which made a 9.2 bench
                // stash read as a startable asset. A percentile is robust to
                // a single weak lineup.
                val p25 = sorted[(sorted.size * 25 / 100).coerceAtMost(sorted.size - 1)]

                position to PositionLevel(position, median, p25, sorted.first(), sorted.size)
            }.toMap()

            return ReplacementLevel(levels)
        }
    }
}
