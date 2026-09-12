package com.aviato.fantasybrief.data

import org.json.JSONObject

/**
 * Turns raw ESPN JSON into the domain model.
 *
 * Written entirely with opt* accessors: every field is treated as possibly
 * absent, because this API is undocumented and adds/removes fields between
 * seasons. A missing field should degrade a row, never crash the app.
 */
object LeagueParser {

    private const val STAT_ID_RECEPTIONS = 53

    fun parse(raw: String, leagueId: Long, season: Int, swid: String?): League {
        val root = JSONObject(raw)
        val settings = parseSettings(root)
        val scoringPeriod = root.optInt("scoringPeriodId", 1)

        val teamsJson = root.optJSONArray("teams")
        val teams = buildList {
            for (i in 0 until (teamsJson?.length() ?: 0)) {
                teamsJson?.optJSONObject(i)?.let { add(parseTeam(it, scoringPeriod)) }
            }
        }

        val mine = teams.firstOrNull { it.ownedBy(swid) }
        return League(leagueId, season, settings, teams, mine?.id)
    }

    // ---- settings ---------------------------------------------------------

    private fun parseSettings(root: JSONObject): LeagueSettings {
        val s = root.optJSONObject("settings") ?: JSONObject()
        val roster = s.optJSONObject("rosterSettings") ?: JSONObject()
        val scoring = s.optJSONObject("scoringSettings") ?: JSONObject()
        val acq = s.optJSONObject("acquisitionSettings") ?: JSONObject()
        val trade = s.optJSONObject("tradeSettings") ?: JSONObject()
        val status = root.optJSONObject("status") ?: JSONObject()

        // lineupSlotCounts: keys are slot ids as STRINGS, values are counts.
        val counts = roster.optJSONObject("lineupSlotCounts") ?: JSONObject()
        val starters = mutableListOf<SlotRule>()
        var bench = 0
        var ir = 0
        for (key in counts.keys()) {
            val slotId = key.toIntOrNull() ?: continue
            val n = counts.optInt(key, 0)
            if (n <= 0) continue
            when (slotId) {
                20 -> bench = n
                21 -> ir = n
                24 -> Unit
                else -> starters.add(SlotRule(slotId, n))
            }
        }
        starters.sortBy { Enums.slotOrder(it.slotId) }

        // scoringType is the MATCHUP format (H2H_POINTS), not PPR. Real
        // scoring lives in scoringItems at statId 53 = receptions.
        var receptionPoints: Double? = null
        val items = scoring.optJSONArray("scoringItems")
        for (i in 0 until (items?.length() ?: 0)) {
            val item = items?.optJSONObject(i) ?: continue
            if (item.optInt("statId", -1) == STAT_ID_RECEPTIONS) {
                receptionPoints = item.optDouble("points", 0.0)
                break
            }
        }
        val scoringLabel = when {
            receptionPoints == null -> scoring.optString("playerRankType", "Unknown")
            receptionPoints >= 0.99 -> "PPR"
            receptionPoints >= 0.4 -> "Half PPR"
            else -> "Standard"
        }

        return LeagueSettings(
            name = s.optString("name", "League"),
            size = s.optInt("size", 0),
            scoringLabel = scoringLabel,
            receptionPoints = receptionPoints,
            matchupFormat = scoring.optString("scoringType", ""),
            starterSlots = starters,
            benchCount = bench,
            irCount = ir,
            waiverType = acq.optString("acquisitionType", "UNKNOWN"),
            waiverHours = acq.optInt("waiverHours", 0),
            waiverOrderResets = acq.optBoolean("waiverOrderReset", false),
            waiverProcessDays = acq.optJSONArray("waiverProcessDays")?.let { a ->
                buildList { for (i in 0 until a.length()) add(a.optString(i)) }
            } ?: emptyList(),
            waiverProcessHour = acq.optInt("waiverProcessHour", 11),
            waiverRuns = status.optJSONObject("waiverProcessStatus")?.let { w ->
                buildList {
                    w.keys().forEach { iso ->
                        runCatching {
                            val f = java.text.SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US
                            )
                            f.parse(iso)?.time?.let { add(it) }
                        }
                    }
                }.sorted()
            } ?: emptyList(),
            acquisitionLimit = acq.optInt("acquisitionLimit", -1),
            // acquisitionBudget is a decoy default when this flag is false.
            usesFaab = acq.optBoolean("isUsingAcquisitionBudget", false),
            tradeDeadlineMillis = trade.optLong("deadlineDate", 0L),
            tradeReviewHours = trade.optInt("revisionHours", 0),
            currentMatchupPeriod = status.optInt("currentMatchupPeriod", 1),
            scoringPeriodId = root.optInt("scoringPeriodId", 1)
        )
    }

    // ---- teams ------------------------------------------------------------

    private fun parseTeam(t: JSONObject, scoringPeriod: Int): FantasyTeam {
        val overall = t.optJSONObject("record")?.optJSONObject("overall")
        val tx = t.optJSONObject("transactionCounter")

        val ownersJson = t.optJSONArray("owners")
        val owners = buildList {
            for (i in 0 until (ownersJson?.length() ?: 0)) {
                ownersJson?.optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }

        val entries = t.optJSONObject("roster")?.optJSONArray("entries")
        val players = buildList {
            for (i in 0 until (entries?.length() ?: 0)) {
                entries?.optJSONObject(i)?.let { add(parseRosterEntry(it, scoringPeriod)) }
            }
        }.sortedWith(
            compareBy({ Enums.slotOrder(it.lineupSlotId) }, { -(it.projection ?: 0.0) })
        )

        return FantasyTeam(
            id = t.optInt("id", -1),
            name = t.optString("name", "Team ${t.optInt("id", -1)}"),
            abbrev = t.optString("abbrev", ""),
            owners = owners,
            primaryOwner = t.optString("primaryOwner", null),
            wins = overall?.optInt("wins", 0) ?: 0,
            losses = overall?.optInt("losses", 0) ?: 0,
            ties = overall?.optInt("ties", 0) ?: 0,
            projectedRank = t.optInt("currentProjectedRank", 0),
            draftDayRank = t.optInt("draftDayProjectedRank", 0),
            waiverRank = t.optInt("waiverRank", 0),
            logoUrl = t.optString("logo", "").ifBlank { null },
            pointsFor = overall?.optDouble("pointsFor", 0.0) ?: 0.0,
            acquisitions = tx?.optInt("acquisitions", 0) ?: 0,
            drops = tx?.optInt("drops", 0) ?: 0,
            trades = tx?.optInt("trades", 0) ?: 0,
            roster = players
        )
    }

    private fun parseRosterEntry(entry: JSONObject, scoringPeriod: Int): RosterPlayer {
        val pool = entry.optJSONObject("playerPoolEntry")
        val p = pool?.optJSONObject("player") ?: JSONObject()
        val ownership = p.optJSONObject("ownership")

        val eligibleJson = p.optJSONArray("eligibleSlots")
        val eligible = buildList {
            for (i in 0 until (eligibleJson?.length() ?: 0)) {
                add(eligibleJson?.optInt(i) ?: continue)
            }
        }

        return RosterPlayer(
            playerId = p.optInt("id", entry.optInt("playerId", -1)),
            name = p.optString("fullName", "Unknown"),
            positionId = p.optInt("defaultPositionId", -1),
            proTeamId = p.optInt("proTeamId", 0),
            lineupSlotId = entry.optInt("lineupSlotId", 20),
            // D/ST has no injuryStatus field at all. optString gives "" there,
            // which isHealthy treats as healthy.
            injuryStatus = p.optString("injuryStatus", "").ifBlank { null },
            injured = p.optBoolean("injured", false),
            projection = weeklyProjection(p, scoringPeriod),
            seasonActual = seasonActual(p),
            gamesPlayed = gamesPlayed(p),
            weeklyActuals = weeklyActuals(p),
            percentOwned = ownership?.optDouble("percentOwned")?.takeIf { !it.isNaN() },
            percentChange = ownership?.optDouble("percentChange")?.takeIf { !it.isNaN() },
            eligibleSlots = eligible,
            // transactionCounter.acquisitions reads 0 for every team even
            // after confirmed claims. These per-player fields on the roster
            // entry are the reliable source, and they carry the WHEN too.
            acquisitionType = entry.optString("acquisitionType", "").ifBlank { null },
            acquisitionMillis = entry.optLong("acquisitionDate", 0L)
        )
    }

    /**
     * Projection lives in player.stats[]. The row we want has
     * statSourceId == 1 (projected, not actual) and statSplitTypeId == 1
     * (single week), for the current scoring period.
     */
    /**
     * Season-to-date ACTUAL points: statSourceId 0 (actual, not projected)
     * and statSplitTypeId 0 (season, not single week).
     *
     * Returns null rather than 0.0 before any games are played — a real zero
     * and "no games yet" must not render identically.
     */
    private fun seasonActual(player: JSONObject): Double? {
        val stats = player.optJSONArray("stats") ?: return null
        for (i in 0 until stats.length()) {
            val row = stats.optJSONObject(i) ?: continue
            if (row.optInt("statSourceId", -1) != 0) continue
            if (row.optInt("statSplitTypeId", -1) != 0) continue
            val total = row.optDouble("appliedTotal", Double.NaN)
            if (!total.isNaN()) return total
        }
        return null
    }

    /**
     * Weekly ACTUAL points per scoring period, for scoring past predictions.
     * statSourceId 0 is actual, statSplitTypeId 1 is a single week.
     */
    fun weeklyActuals(player: JSONObject): Map<Int, Double> {
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

    /** Counts weekly ACTUAL rows, which only exist once a game is played. */
    private fun gamesPlayed(player: JSONObject): Int {
        val stats = player.optJSONArray("stats") ?: return 0
        var count = 0
        for (i in 0 until stats.length()) {
            val row = stats.optJSONObject(i) ?: continue
            if (row.optInt("statSourceId", -1) != 0) continue
            if (row.optInt("statSplitTypeId", -1) != 1) continue
            count++
        }
        return count
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
