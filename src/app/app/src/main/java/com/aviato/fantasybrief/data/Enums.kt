package com.aviato.fantasybrief.data

/** ESPN's magic numbers. Every one of these cost someone a failed request. */
object Enums {

    /**
     * Real NFL proTeamIds. 0 is the free agent placeholder and 31/32
     * are unused — requesting them guaranteed two failed depth chart
     * fetches and a permanent "missing for 2 teams" note.
     */
    val REAL_PRO_TEAMS: Set<Int> = ((1..30) + listOf(33, 34)).toSet()

    /**
     * Display order for starting slots. ESPN returns DST before K;
     * a kicker reads as the last skill slot and the defence as the
     * closer, which is how every fantasy site lays it out.
     */
    fun slotSortKey(slotId: Int): Int = when (slotId) {
        17 -> 90   // K
        16 -> 91   // DST
        else -> slotId
    }

    /** player.defaultPositionId */
    fun position(id: Int): String = when (id) {
        1 -> "QB"; 2 -> "RB"; 3 -> "WR"; 4 -> "TE"; 5 -> "K"
        7 -> "P"; 9 -> "DT"; 10 -> "DE"; 11 -> "LB"; 12 -> "CB"
        13 -> "S"; 16 -> "DST"
        else -> "?$id"
    }

    /** rosterEntry.lineupSlotId — also the keys of lineupSlotCounts. */
    fun slot(id: Int): String = when (id) {
        0 -> "QB"; 1 -> "TQB"; 2 -> "RB"; 3 -> "FLEX"; 4 -> "WR"
        5 -> "WR/TE"; 6 -> "TE"; 7 -> "OP"; 8 -> "DT"; 9 -> "DE"
        10 -> "LB"; 11 -> "DL"; 12 -> "CB"; 13 -> "S"; 14 -> "DB"
        15 -> "DP"; 16 -> "DST"; 17 -> "K"; 18 -> "P"; 19 -> "HC"
        20 -> "BE"; 21 -> "IR"; 23 -> "FLEX+"; 24 -> "ER"
        else -> "S$id"
    }

    /**
     * Which positions a slot accepts. Never assume slot 3 semantics —
     * this is what your spec warns about, and wrong flex eligibility
     * produces wrong lineup advice, which is the whole product.
     */
    fun slotEligibility(id: Int): String? = when (id) {
        3 -> "RB/WR"
        5 -> "WR/TE"
        7 -> "QB/RB/WR/TE"
        23 -> "RB/WR/TE"
        else -> null
    }

    fun isStarterSlot(id: Int): Boolean = id != 20 && id != 21 && id != 24

    /** Display order for a roster listing. Lower sorts first. */
    fun slotOrder(id: Int): Int = when (id) {
        0 -> 0; 1 -> 1; 2 -> 2; 4 -> 3; 6 -> 4
        3 -> 5; 23 -> 6; 5 -> 7; 7 -> 8
        16 -> 9; 17 -> 10
        20 -> 50; 21 -> 60; 24 -> 61
        else -> 30 + id
    }

    fun proTeam(id: Int): String = when (id) {
        0 -> "FA"; 1 -> "ATL"; 2 -> "BUF"; 3 -> "CHI"; 4 -> "CIN"
        5 -> "CLE"; 6 -> "DAL"; 7 -> "DEN"; 8 -> "DET"; 9 -> "GB"
        10 -> "TEN"; 11 -> "IND"; 12 -> "KC"; 13 -> "LV"; 14 -> "LAR"
        15 -> "MIA"; 16 -> "MIN"; 17 -> "NE"; 18 -> "NO"; 19 -> "NYG"
        20 -> "NYJ"; 21 -> "PHI"; 22 -> "ARI"; 23 -> "PIT"; 24 -> "LAC"
        25 -> "SF"; 26 -> "SEA"; 27 -> "TB"; 28 -> "WSH"; 29 -> "CAR"
        30 -> "JAX"; 33 -> "BAL"; 34 -> "HOU"
        else -> "T$id"
    }

    /** Reverse of proTeam(). The site API keys on abbreviation, not id. */
    fun proTeamIdFor(abbrev: String): Int? {
        if (abbrev.isBlank()) return null
        val a = abbrev.uppercase()
        return (1..34).firstOrNull { proTeam(it) == a }
    }

    /**
     * 2026 returns "ACTIVE" for healthy players — the OPPOSITE of what the
     * Ruby version found. Accept every known healthy spelling, and prefer
     * the `injured` boolean where available. D/ST has no field at all.
     */
    private val HEALTHY = setOf("ACTIVE", "NORMAL", "")

    fun isHealthy(injuryStatus: String?, injuredFlag: Boolean?): Boolean {
        if (injuredFlag == true) return false
        if (injuryStatus == null) return true
        return injuryStatus.uppercase() in HEALTHY
    }

    fun injuryShort(status: String?): String = when (status?.uppercase()) {
        null, "ACTIVE", "NORMAL", "" -> ""
        "QUESTIONABLE" -> "Q"; "DOUBTFUL" -> "D"; "OUT" -> "O"
        "INJURY_RESERVE" -> "IR"; "SUSPENSION" -> "SUS"
        "PROBABLE" -> "P"; "DAY_TO_DAY" -> "DTD"
        else -> status.take(3).uppercase()
    }
}
