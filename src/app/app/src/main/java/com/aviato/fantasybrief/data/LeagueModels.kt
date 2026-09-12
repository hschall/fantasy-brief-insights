package com.aviato.fantasybrief.data

data class RosterPlayer(
    val playerId: Int,
    val name: String,
    val positionId: Int,
    val proTeamId: Int,
    val lineupSlotId: Int,
    val injuryStatus: String?,
    val injured: Boolean,
    val projection: Double?,
    /** Season-to-date ACTUAL points. Null before any games are played. */
    val seasonActual: Double?,
    /** Games with a recorded result, for a per-game average. */
    val gamesPlayed: Int,
    /** scoringPeriodId -> actual points, for settling past predictions. */
    val weeklyActuals: Map<Int, Double>,
    val percentOwned: Double?,
    val percentChange: Double?,
    val eligibleSlots: List<Int>,
    /** DRAFT, ADD, WAIVER, TRADE. Null on older rows. */
    val acquisitionType: String?,
    val acquisitionMillis: Long
) {
    val position: String get() = Enums.position(positionId)
    val proTeam: String get() = Enums.proTeam(proTeamId)
    val slot: String get() = Enums.slot(lineupSlotId)
    val isStarter: Boolean get() = Enums.isStarterSlot(lineupSlotId)
    val healthy: Boolean get() = Enums.isHealthy(injuryStatus, injured)
    val injuryTag: String get() = Enums.injuryShort(injuryStatus)

    val wasDrafted: Boolean get() = acquisitionType == null ||
        acquisitionType.uppercase().contains("DRAFT")

    /** Actual points per game. The number projections keep failing to match. */
    val pointsPerGame: Double?
        get() = if (gamesPlayed > 0 && seasonActual != null)
            seasonActual / gamesPlayed else null

    /**
     * How far this week's projection sits from the player's own average.
     * Positive means ESPN expects more than he has delivered.
     */
    val projectionGap: Double?
        get() {
            val ppg = pointsPerGame ?: return null
            val proj = projection ?: return null
            return proj - ppg
        }
}

data class FantasyTeam(
    val id: Int,
    val name: String,
    val abbrev: String,
    val owners: List<String>,
    val primaryOwner: String?,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val projectedRank: Int,        // currentProjectedRank, live
    val draftDayRank: Int,         // frozen at the draft, never mutates
    val waiverRank: Int,
    /** ESPN CDN url. SVG for stock and logo packs, JPEG for uploads. */
    val logoUrl: String?,
    val pointsFor: Double,
    val acquisitions: Int,
    val drops: Int,
    val trades: Int,
    val roster: List<RosterPlayer>
) {
    /**
     * Movement since the draft. Positive means climbing.
     *
     * Treat this as CHANGE DETECTION, not a truth claim — it is computed from
     * ESPN season projections, which this project distrusts. It answers "has
     * something happened to this roster", not "is this roster good".
     */
    val rankDelta: Int get() = if (draftDayRank > 0) draftDayRank - projectedRank else 0

    val record: String get() = if (ties > 0) "$wins-$losses-$ties" else "$wins-$losses"

    fun ownedBy(swid: String?): Boolean {
        if (swid.isNullOrBlank()) return false
        val s = swid.uppercase()
        return primaryOwner?.uppercase() == s || owners.any { it.uppercase() == s }
    }
}

/** One lineup slot and how many of it the league starts. */
data class SlotRule(val slotId: Int, val count: Int) {
    val label: String get() = Enums.slot(slotId)
    val eligibility: String? get() = Enums.slotEligibility(slotId)
}

data class LeagueSettings(
    val name: String,
    val size: Int,
    val scoringLabel: String,        // PPR / Half PPR / Standard
    val receptionPoints: Double?,
    val matchupFormat: String,       // H2H_POINTS etc
    val starterSlots: List<SlotRule>,
    val benchCount: Int,
    val irCount: Int,
    val waiverType: String,
    val waiverHours: Int,
    val waiverOrderResets: Boolean,
    val waiverProcessDays: List<String>,
    val waiverProcessHour: Int,
    /** Epoch millis of runs ESPN actually performed. Beats the settings. */
    val waiverRuns: List<Long>,
    val acquisitionLimit: Int,       // -1 means unlimited
    val usesFaab: Boolean,
    val tradeDeadlineMillis: Long,
    val tradeReviewHours: Int,
    val currentMatchupPeriod: Int,
    val scoringPeriodId: Int
) {
    /** Human lineup string, derived — never hardcoded. */
    val lineupString: String
        get() {
            val parts = starterSlots.map { rule ->
                val elig = rule.eligibility
                if (elig != null) "${rule.count} ${rule.label} ($elig)"
                else "${rule.count} ${rule.label}"
            }
            return parts.joinToString(", ") + ", $benchCount bench" +
                if (irCount > 0) ", $irCount IR" else ""
        }

    val waiverSummary: String
        get() = buildString {
            append(waiverType.lowercase().replace('_', ' '))
            append(", ").append(waiverHours).append("h period")
            // State BOTH cases explicitly. Omitting the phrase when false made
            // a rolling-priority league read identically to a resetting one,
            // which inverts whether a claim is cheap or expensive.
            append(
                if (waiverOrderResets)
                    ", order RESETS weekly (priority is not a resource to conserve)"
                else ", order does NOT reset (spending priority drops you to last)"
            )
            append(if (acquisitionLimit < 0) ", no acquisition limit"
                   else ", limit $acquisitionLimit")
        }

    /** Rules that differ from the common defaults and change how you play. */
    val ruleWarnings: List<String>
        get() = buildList {
            if (!waiverOrderResets) {
                add("Rolling waiver priority — a claim costs you position")
            }
            if (tradeReviewHours > 0) {
                add("Trades face a ${tradeReviewHours}h review window")
            }
        }
}

data class League(
    val id: Long,
    val season: Int,
    val settings: LeagueSettings,
    val teams: List<FantasyTeam>,
    val myTeamId: Int?
) {
    val myTeam: FantasyTeam? get() = teams.firstOrNull { it.id == myTeamId }
}
