package com.aviato.fantasybrief.data

import org.json.JSONObject

/**
 * Real NFL game state for one team's game this week.
 *
 * The app has been inferring "has this player's game started" by comparing the
 * kickoff timestamp against the device clock. That is wrong on a delay, cannot
 * distinguish in-progress from final, and has no notion of a quarter.
 */
data class GameState(
    val proTeamId: Int,
    val opponentAbbrev: String,
    val isHome: Boolean,
    val myScore: Int?,
    val theirScore: Int?,
    val state: String,          // pre | in | post
    val statusText: String,     // "FINAL", "Q3 07:12", "SUN 4:05"
    val kickoffMillis: Long,
    /** Quarter, 1-4 (or higher in overtime). 0 before kickoff. */
    val period: Int = 0,
    /** Clock left in the quarter, "07:12". Empty when not in progress. */
    val clock: String = ""
) {
    val started: Boolean get() = state != "pre"
    val finished: Boolean get() = state == "post"

    /**
     * How much of his game is left, 0..1.
     *
     * Used to pro-rate a pregame projection against points already scored.
     * VERIFIED 2026-09-09 against a live week: summing
     * actual + pregame * fractionRemaining across a roster landed within 3.5
     * points of ESPN's own totalProjectedPointsLive on a 111-point total.
     * We run slightly HIGH because we credit the full remaining projection to
     * a player already beating it, which ESPN evidently discounts.
     */
    val fractionRemaining: Double
        get() = when {
            !started -> 1.0
            finished -> 0.0
            else -> {
                val parts = clock.split(":")
                val secs = if (parts.size == 2)
                    (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
                else 0
                val leftInQuarter = secs / 900.0
                (((4 - period).coerceAtLeast(0)) + leftInQuarter) / 4.0
            }
        }.coerceIn(0.0, 1.0)

    /** "DEN 27 vs LV 17 (W)" once there are scores, else "DEN vs LV". */
    fun line(myAbbrev: String): String {
        val vs = if (isHome) "vs" else "@"
        if (myScore == null || theirScore == null || !started) {
            return "$myAbbrev $vs $opponentAbbrev"
        }
        val result = when {
            !finished -> ""
            myScore > theirScore -> " (W)"
            myScore < theirScore -> " (L)"
            else -> " (T)"
        }
        return "$myAbbrev $myScore $vs $opponentAbbrev $theirScore$result"
    }
}

/**
 * ESPN's public NFL scoreboard. One request covers every game in a week.
 *
 * UNVERIFIED SHAPE — this parser is written against the widely-documented
 * form of this endpoint but has NOT been probed against a live response in
 * this project. Every field is read with opt* accessors and a parse failure
 * returns an empty map, so the Matchup screen degrades to kickoff-time
 * inference rather than breaking. Run the Scoreboard probe in More to confirm.
 */
class NflScoreboardClient {

    private val http = PublicEspnClient()

    fun load(week: Int, season: Int): Map<Int, GameState> {
        val url = "${PublicEspnClient.SITE}/apis/site/v2/sports/football/nfl/" +
            "scoreboard?seasontype=2&week=$week&dates=$season"
        val res = http.get(url)
        if (!res.ok) return emptyMap()
        return runCatching { parse(res.body) }.getOrDefault(emptyMap())
    }

    fun rawFor(week: Int, season: Int): PublicEspnClient.Result = http.get(
        "${PublicEspnClient.SITE}/apis/site/v2/sports/football/nfl/" +
            "scoreboard?seasontype=2&week=$week&dates=$season"
    )

    /** ESPN sends "2026-09-10T00:20Z", which is not what SimpleDateFormat
     *  expects by default — the Z has to be handled explicitly. */
    private fun parseIso(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            f.parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun localKickoff(millis: Long): String =
        java.text.SimpleDateFormat("EEE h:mma", java.util.Locale.US)
            .format(java.util.Date(millis))
            .replace("AM", "am").replace("PM", "pm")
            .uppercase()

    fun parse(raw: String): Map<Int, GameState> {
        val events = JSONObject(raw).optJSONArray("events") ?: return emptyMap()
        val out = mutableMapOf<Int, GameState>()

        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val comp = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val competitors = comp.optJSONArray("competitors") ?: continue
            if (competitors.length() < 2) continue

            val status = comp.optJSONObject("status") ?: event.optJSONObject("status")
            val type = status?.optJSONObject("type")
            val state = type?.optString("state", "pre") ?: "pre"
            val detail = type?.optString("shortDetail", "") ?: ""
            val period = status?.optInt("period", 0) ?: 0
            val clock = status?.optString("displayClock", "") ?: ""

            // shortDetail is pre-formatted in Eastern ("9/13 - 1:00 PM EDT").
            // The app's own kickoff labels are device-local, and two clocks on
            // one screen is worse than either. Format the ISO date ourselves.
            val kickMillis = parseIso(comp.optString("date", "")
                .ifBlank { event.optString("date", "") })
            val statusText = when (state) {
                "post" -> "FINAL"
                "in" -> if (period > 0) "Q$period $clock".trim() else detail
                else -> if (kickMillis > 0) localKickoff(kickMillis) else detail
            }

            // ESPN's site team id is not the fantasy proTeamId, so match on
            // the abbreviation, which both sides agree on.
            val sides = (0 until competitors.length()).mapNotNull { c ->
                val comp2 = competitors.optJSONObject(c) ?: return@mapNotNull null
                val abbrev = comp2.optJSONObject("team")?.optString("abbreviation", "")
                    ?: return@mapNotNull null
                Triple(
                    abbrev,
                    comp2.optString("score", "").toIntOrNull(),
                    comp2.optString("homeAway", "") == "home"
                )
            }
            if (sides.size < 2) continue

            sides.forEachIndexed { idx, (abbrev, score, isHome) ->
                val other = sides[1 - idx]
                val proId = Enums.proTeamIdFor(abbrev) ?: return@forEachIndexed
                out[proId] = GameState(
                    proTeamId = proId,
                    opponentAbbrev = other.first,
                    isHome = isHome,
                    myScore = score,
                    theirScore = other.second,
                    state = state,
                    statusText = statusText,
                    kickoffMillis = kickMillis,
                    period = period,
                    clock = clock
                )
            }
        }
        return out
    }
}
