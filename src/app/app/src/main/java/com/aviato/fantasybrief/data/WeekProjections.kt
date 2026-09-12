package com.aviato.fantasybrief.data

/**
 * Projections for a week other than the current one.
 *
 * VERIFIED 2026-09-07: mRoster honours the scoringPeriodId query parameter and
 * returns a statSourceId=1, statSplitTypeId=1 row for that period. Asking for
 * week 9 in week 1 returned 18.9 for Amon-Ra St. Brown — a real forward
 * projection, not a repeat of the current week.
 *
 * The response also carries the preceding week's row, which is ignored.
 *
 * ~1.2 MB per request, so this is fetched on demand when a week is selected
 * and cached for the session rather than pulled on every refresh.
 */
class WeekProjections(private val client: EspnClient) {

    private val cache = mutableMapOf<String, Map<Int, Double>>()

    /** playerId to projected points for that week. Empty on failure. */
    fun load(season: Int, leagueId: Long, week: Int): Map<Int, Double> {
        val key = "$leagueId:$season:$week"
        cache[key]?.let { return it }

        val url = client.leagueUrl(season, leagueId, "mRoster") +
            "&scoringPeriodId=$week"
        val res = client.get(url)
        if (!res.ok) return emptyMap()

        val out = mutableMapOf<Int, Double>()
        runCatching {
            val teams = org.json.JSONObject(res.body).optJSONArray("teams")
            for (t in 0 until (teams?.length() ?: 0)) {
                val entries = teams?.optJSONObject(t)?.optJSONObject("roster")
                    ?.optJSONArray("entries") ?: continue
                for (e in 0 until entries.length()) {
                    val player = entries.optJSONObject(e)
                        ?.optJSONObject("playerPoolEntry")?.optJSONObject("player")
                        ?: continue
                    // D/ST ids are negative by design: -16000 - proTeamId.
                    // Filtering them out silently left every defence on the
                    // current week's projection.
                    val id = player.optInt("id", 0)
                    if (id == 0) continue
                    val stats = player.optJSONArray("stats") ?: continue
                    for (i in 0 until stats.length()) {
                        val row = stats.optJSONObject(i) ?: continue
                        // The requested week only — the response also includes
                        // the week before it.
                        if (row.optInt("statSourceId", -1) != 1) continue
                        if (row.optInt("statSplitTypeId", -1) != 1) continue
                        if (row.optInt("scoringPeriodId", -1) != week) continue
                        val total = row.optDouble("appliedTotal", Double.NaN)
                        if (!total.isNaN()) out[id] = total
                    }
                }
            }
        }
        if (out.isNotEmpty()) cache[key] = out
        return out
    }
}
