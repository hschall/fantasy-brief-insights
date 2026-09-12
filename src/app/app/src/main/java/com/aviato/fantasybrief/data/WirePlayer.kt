package com.aviato.fantasybrief.data

import org.json.JSONObject

data class WirePlayer(
    val playerId: Int,
    val name: String,
    val positionId: Int,
    val proTeamId: Int,
    val injuryStatus: String?,
    val injured: Boolean,
    val projection: Double?,
    val percentOwned: Double,
    val percentChange: Double,
    val percentStarted: Double?,
    val status: String,          // FREEAGENT or WAIVERS
    val waiverProcessMillis: Long,
    /** scoringPeriodId -> ACTUAL points. Empty before any games. */
    val weeklyActuals: Map<Int, Double> = emptyMap(),
    /** Analyst consensus for this week. Null when unranked. */
    val ranking: PlayerRanking? = null
) {
    val position: String get() = Enums.position(positionId)
    val healthy: Boolean get() = Enums.isHealthy(injuryStatus, injured)
    val injuryTag: String get() = Enums.injuryShort(injuryStatus)

    /**
     * The money signal from your spec: low absolute ownership plus a fast
     * rise. Not a score — a boolean gate. Ranking within it is by delta.
     */
    val isMoneySignal: Boolean
        get() = percentOwned < 25.0 && percentChange >= 1.5
}

object WireParser {

    fun parse(raw: String, scoringPeriod: Int): List<WirePlayer> {
        val players = JSONObject(raw).optJSONArray("players") ?: return emptyList()
        return buildList {
            for (i in 0 until players.length()) {
                val entry = players.optJSONObject(i) ?: continue
                val p = entry.optJSONObject("player") ?: continue
                val ownership = p.optJSONObject("ownership")

                add(
                    WirePlayer(
                        playerId = p.optInt("id", -1),
                        name = p.optString("fullName", "Unknown"),
                        positionId = p.optInt("defaultPositionId", -1),
                        proTeamId = p.optInt("proTeamId", 0),
                        injuryStatus = p.optString("injuryStatus", "").ifBlank { null },
                        injured = p.optBoolean("injured", false),
                        projection = weeklyProjection(p, scoringPeriod),
                        percentOwned = ownership?.optDouble("percentOwned", 0.0)
                            ?.takeIf { !it.isNaN() } ?: 0.0,
                        percentChange = ownership?.optDouble("percentChange", 0.0)
                            ?.takeIf { !it.isNaN() } ?: 0.0,
                        percentStarted = ownership?.optDouble("percentStarted")
                            ?.takeIf { !it.isNaN() },
                        status = entry.optString("status", ""),
                        waiverProcessMillis = entry.optLong("waiverProcessDate", 0L),
                        weeklyActuals = weeklyActuals(p),
                        ranking = Rankings.parse(
                            p, scoringPeriod,
                            Enums.position(p.optInt("defaultPositionId"))
                        )
                    )
                )
            }
        }
    }

    /**
     * Per-week ACTUAL points. statSourceId 0 is actual, statSplitTypeId 1 is a
     * single week.
     *
     * Absent before any games are played, and possibly absent from this view
     * entirely — verified 2026-09-07 that the wire response carries season
     * totals and the current projection but no weekly actuals yet. If these
     * never appear once games exist, the fallback is a scoringPeriodId-scoped
     * request, which is known to work on mRoster.
     */
    private fun weeklyActuals(player: JSONObject): Map<Int, Double> {
        val stats = player.optJSONArray("stats") ?: return emptyMap()
        val out = mutableMapOf<Int, Double>()
        for (i in 0 until stats.length()) {
            val row = stats.optJSONObject(i) ?: continue
            if (row.optInt("statSourceId", -1) != 0) continue
            if (row.optInt("statSplitTypeId", -1) != 1) continue
            val week = row.optInt("scoringPeriodId", -1)
            val total = row.optDouble("appliedTotal", Double.NaN)
            if (week > 0 && !total.isNaN()) out[week] = total
        }
        return out
    }

    private fun weeklyProjection(player: JSONObject, scoringPeriod: Int): Double? {
        val stats = player.optJSONArray("stats") ?: return null
        for (i in 0 until stats.length()) {
            val row = stats.optJSONObject(i) ?: continue
            if (row.optInt("statSourceId", -1) != 1) continue
            if (row.optInt("statSplitTypeId", -1) != 1) continue
            if (row.optInt("scoringPeriodId", -1) != scoringPeriod) continue
            val total = row.optDouble("appliedTotal", Double.NaN)
            if (!total.isNaN()) return total
        }
        return null
    }
}
