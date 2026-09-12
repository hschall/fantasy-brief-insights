package com.aviato.fantasybrief.data

/**
 * A wire player with the one number that makes the list a decision: how much
 * he would add to the STARTING lineup, not to the roster.
 */
data class BoardEntry(
    val player: WirePlayer,
    val tier: Tier?,
    /** Projection minus the starter he would displace. Null if he would not. */
    val lineupGain: Double?,
    val replaces: RosterPlayer?,
    val depthLabel: String?,
    /** Rank on his NFL depth chart. Null when we have no chart for him. */
    val depthRank: Int?,
    val droppedAtMillis: Long?,
    val note: String?
)

enum class BoardSort { PROJECTION, LINEUP, TRENDING, OWNED }

object WireBoard {

    // Past this you are into players nobody in a ten-team league starts, and
    // the list stops being scannable on a phone.
    private const val CAP_PER_POSITION = 40
    private const val CAP_ALL = 150

    fun build(
        league: League,
        pool: List<WirePlayer>,
        tiers: Map<Tier, List<TieredPlayer>>,
        depth: DepthCharts,
        transactions: List<Transaction>,
        replacement: ReplacementLevel
    ): List<BoardEntry> {
        val team = league.myTeam
        val tierOf = tiers.flatMap { (t, list) -> list.map { it.player.playerId to t } }.toMap()
        val recentDrops = transactions
            .filter { it.kind == TxKind.DROP }
            .groupBy { it.playerId }
            .mapValues { (_, v) -> v.maxOf { it.whenMillis } }

        return pool.map { p ->
            // The starter he would actually displace, at his own position.
            val worst = team?.roster
                ?.filter { it.isStarter && it.position == p.position }
                ?.minByOrNull { it.projection ?: 0.0 }
            val gain = if (worst == null) null
                       else (p.projection ?: 0.0) - (worst.projection ?: 0.0)

            val rank = depth.rank(p.proTeamId, p.playerId)
            val note = when {
                rank != null && rank <= 2 && (p.projection ?: 0.0) < 4.0 ->
                    "Depth rank $rank with a projection that has not caught up."
                p.percentChange >= 2.0 ->
                    "Ownership climbing while still at ${fmt(p.percentOwned)}%."
                else -> null
            }

            BoardEntry(
                player = p,
                tier = tierOf[p.playerId],
                lineupGain = gain,
                replaces = if ((gain ?: 0.0) > 0) worst else null,
                depthLabel = depth.label(p.proTeamId, p.playerId),
                depthRank = rank,
                droppedAtMillis = recentDrops[p.playerId],
                note = note
            )
        }
    }

    /**
     * Sorting is the primary control when browsing. PROJECTION is the default
     * because sorting by lineup gain buries the whole pool below zero for
     * anyone with a decent starting lineup — which is most weeks.
     */
    /**
     * @param startersOnly depth rank 1 only — the man currently taking the
     *   snaps at his position, not a backup with a good projection. Legitimately
     *   returns nothing: an available NFL starter is rare and an empty list is
     *   the honest answer.
     */
    fun view(
        entries: List<BoardEntry>,
        position: String?,
        sort: BoardSort,
        startersOnly: Boolean = false
    ): List<BoardEntry> {
        val filtered = entries
            .filter { position == null || it.player.position == position }
            .filter { !startersOnly || it.depthRank == 1 }
        val sorted = when (sort) {
            BoardSort.PROJECTION -> filtered.sortedByDescending { it.player.projection ?: 0.0 }
            BoardSort.LINEUP -> filtered.sortedByDescending { it.lineupGain ?: -999.0 }
            BoardSort.TRENDING -> filtered.sortedByDescending { it.player.percentChange }
            // What the rest of the market thinks, independent of what he
            // projects this week.
            BoardSort.OWNED -> filtered.sortedByDescending { it.player.percentOwned }
        }
        return sorted.take(if (position == null) CAP_ALL else CAP_PER_POSITION)
    }

    /**
     * Everything available that would improve the starting lineup, best
     * first. Never a lineup fix — Today owns those.
     */
    fun bestAvailable(entries: List<BoardEntry>, limit: Int = 5): List<BoardEntry> =
        entries.filter { (it.lineupGain ?: 0.0) > 0 }
            .sortedByDescending { it.lineupGain ?: 0.0 }
            .take(limit)

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.0f", v)
}
