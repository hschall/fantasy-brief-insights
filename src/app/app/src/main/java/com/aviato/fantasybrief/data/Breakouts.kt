package com.aviato.fantasybrief.data

/**
 * An available player who just scored, with the projection that failed to see
 * it coming.
 *
 * The app cannot tell a role change from a fluke — a 22-point week is either a
 * new job or one long touchdown. That distinction lives in news, which is
 * exactly why this belongs in the dump rather than on a screen.
 */
data class Breakout(
    val player: WirePlayer,
    val week: Int,
    val actual: Double,
    val projected: Double,
    val depthRank: Int?,
    val ownershipDelta: Double
) {
    /** How far past his projection he finished. The size of the surprise. */
    val surprise: Double get() = actual - projected
}

object Breakouts {

    private const val MIN_SCORE = 8.0
    private const val MIN_SURPRISE = 4.0

    /**
     * @param week the completed week to look at, normally currentWeek - 1
     */
    fun find(pool: List<WirePlayer>, depth: DepthCharts, week: Int): List<Breakout> {
        if (week < 1) return emptyList()
        return pool.mapNotNull { p ->
            val actual = p.weeklyActuals[week] ?: return@mapNotNull null
            // Kickers and defences score in bursts and mean nothing.
            if (p.positionId == 5 || p.positionId == 16) return@mapNotNull null
            if (actual < MIN_SCORE) return@mapNotNull null
            val projected = p.projection ?: 0.0
            if (actual - projected < MIN_SURPRISE) return@mapNotNull null
            Breakout(
                player = p, week = week, actual = actual, projected = projected,
                depthRank = depth.rank(p.proTeamId, p.playerId),
                ownershipDelta = p.percentChange
            )
        }.sortedByDescending { it.actual }
    }
}
