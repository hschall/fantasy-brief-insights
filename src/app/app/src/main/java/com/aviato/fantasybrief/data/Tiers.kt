package com.aviato.fantasybrief.data

/**
 * Projection tier, adjusted by where the analysts rank him.
 *
 * The projection tier stays the base — it is league-relative, measured
 * against the actual starters in THIS league, which a generic rank cutoff
 * would throw away. Rank only ever PROMOTES, one step, because promotion is
 * the safe direction: being told a player is better than the projection
 * suggests costs less than the reverse.
 *
 * Demotion is deliberately absent. A player projected elite and ranked RB40
 * is arguably the more dangerous case, but his rank badge is visible on the
 * card so the disagreement is not hidden. Worth revisiting with a season of
 * evidence about which signal is right more often.
 */
data class TierVerdict(
    val tier: String,
    /** True when rank moved him up. Same colour, marked differently. */
    val promoted: Boolean
)

object Tiers {

    /**
     * Thresholds scale with how many of that position the league starts.
     *
     * A top-10 RB is elite in a ten-team league starting two plus a flex
     * (~21 startable). A top-10 TE is merely average, because only ten are
     * started at all.
     */
    private fun startable(league: League, position: String): Int {
        val teams = league.settings.size
        val flexSlots = setOf(3, 23)
        val own = when (position) {
            "QB" -> 0; "RB" -> 2; "WR" -> 4; "TE" -> 6; "K" -> 17; "DST" -> 16
            else -> -1
        }
        var dedicated = 0
        var flex = 0
        league.settings.starterSlots.forEach { rule ->
            when {
                rule.slotId == own -> dedicated += rule.count
                position in setOf("RB", "WR") && rule.slotId in flexSlots ->
                    flex += rule.count
            }
        }
        // Half weight: one flex is shared between RB and WR, not one each.
        val slots2 = dedicated * 2 + flex
        return ((slots2 * teams) / 2).coerceAtLeast(teams)
    }

    /** Half the startable pool. */
    fun promoteAt(league: League, position: String) =
        (startable(league, position) / 2).coerceAtLeast(2)

    /** Roughly the top 15%: 3 of 21 RBs, 2 of 10 QBs. */
    fun legendaryAt(league: League, position: String) =
        (startable(league, position) * 15 / 100).coerceAtLeast(2)

    fun forPlayer(
        league: League,
        replacement: ReplacementLevel,
        position: String,
        projection: Double?,
        ranking: PlayerRanking?
    ): TierVerdict {
        val base = replacement.rank(position, projection)
            ?: return TierVerdict("BELOW", false)

        // Kickers and defences are excluded. A K1 ranking is not the analysts
        // spotting a difference-maker — kicker scoring is close to random week
        // to week and the ranking mostly reflects last year's team offence.
        // Four of fifteen legendary slots were going to K and DST, which
        // devalues the tier for the positions where it means something.
        if (position == "K" || position == "DST") return TierVerdict(base, false)

        val r = ranking?.consensus ?: return TierVerdict(base, false)

        // Legendary needs BOTH signals at the extreme, so a promoted player
        // can never reach it and the top tier always means agreement.
        if (base == "ELITE" && r <= legendaryAt(league, position)) {
            return TierVerdict("LEGENDARY", false)
        }
        if (r > promoteAt(league, position)) return TierVerdict(base, false)

        return when (base) {
            "BELOW" -> TierVerdict("SOLID", true)
            "SOLID" -> TierVerdict("ELITE", true)
            else -> TierVerdict(base, false)
        }
    }
}
