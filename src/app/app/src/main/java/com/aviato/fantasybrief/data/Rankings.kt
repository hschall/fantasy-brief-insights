package com.aviato.fantasybrief.data

import org.json.JSONObject

/**
 * What the analysts think of a player this week.
 *
 * VERIFIED 2026-09-09: kona_player_info carries a `rankings` block keyed by
 * scoring period ("0" preseason, "1" week 1, and so on), each holding one
 * entry per ranking source. Nine distinct rankSourceIds were present, ranking
 * the same player independently — Gibbs was RB1 for eight of them and RB2 for
 * the ninth.
 *
 * The app already fetches this on every refresh for the wire pool, and that
 * pool is unfiltered, so it covers rostered players too. No extra request.
 *
 * The sources are ANONYMOUS: ESPN exposes numeric ids and no lookup for their
 * names, so this reports consensus and disagreement rather than "Berry says".
 */
data class PlayerRanking(
    val playerId: Int,
    val position: String,
    /** Median rank across sources — the consensus. */
    val consensus: Int,
    val best: Int,
    val worst: Int,
    val sourceCount: Int,
    /** The scoring period these came from. */
    val period: Int,
    /** True when this is preseason data, not the current week. */
    val preseason: Boolean,
    /** Places moved since the last recorded run. Negative is BETTER. */
    val delta: Int? = null
) {

    /** One place is inside the noise of a changing source count. */
    val moved: Boolean get() = (delta ?: 0) != 0 && kotlin.math.abs(delta!!) >= 1
    /** True when the sources genuinely disagree, not just round differently. */
    val contested: Boolean get() = (worst - best) >= 8

    fun label() = "$position$consensus"

    /** Number only, for badges where the position is already on the row. */
    fun badge() = consensus.toString()
    fun spread() = if (best == worst) "all $sourceCount agree" else "$position$best–$position$worst"
}

object Rankings {

    /** PPR only — every league here is PPR, and mixing formats muddies the median. */
    private const val PPR = "PPR"

    /**
     * The lineup slot a ranking is FOR. Entries exist at several slots per
     * player and they are different questions.
     *
     * VERIFIED 2026-09-09: Travis Hunter carries slotId 4 entries ranking him
     * WR75–81 and slotId 14 entries ranking him 1. Slot 14 is a subset — a
     * rookie or IDP board — and blending the two produced "WR1" for a player
     * every WR source has outside the top 70. Filtering to the position's own
     * slot is what makes the median mean one thing.
     */
    private fun slotFor(position: String): Int? = when (position) {
        "QB" -> 0
        "RB" -> 2
        "WR" -> 4
        "TE" -> 6
        "K" -> 17
        "DST" -> 16
        else -> null
    }

    fun parse(player: JSONObject, scoringPeriod: Int, position: String): PlayerRanking? {
        val all = player.optJSONObject("rankings") ?: return null
        // Preseason is only an acceptable answer BEFORE week 1. After that
        // it is August's opinion dressed as this week's, which is worse than
        // showing nothing.
        val weekly = all.optJSONArray(scoringPeriod.toString())
        val arr = weekly
            ?: (if (scoringPeriod <= 1) all.optJSONArray("0") else null)
            ?: return null
        val isPreseason = weekly == null

        val ranks = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("rankType") != PPR) continue
            // Same player, different boards — take only his own position.
            val slot = slotFor(position)
            if (slot != null && e.optInt("slotId", -1) != slot) continue
            // Source 0 is ESPN's own composite and carries rank 0 with an
            // averageRank instead — including it would drag every median to
            // zero.
            if (e.optInt("rankSourceId", -1) == 0) continue
            if (!e.optBoolean("published", false)) continue
            val r = e.optInt("rank", 0)
            if (r > 0) ranks.add(r)
        }
        if (ranks.isEmpty()) return null

        ranks.sort()
        return PlayerRanking(
            playerId = player.optInt("id"),
            position = position,
            consensus = ranks[ranks.size / 2],
            best = ranks.first(),
            worst = ranks.last(),
            sourceCount = ranks.size,
            period = if (isPreseason) 0 else scoringPeriod,
            preseason = isPreseason
        )
    }
}
