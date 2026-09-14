package com.aviato.fantasybrief.data

import org.json.JSONObject

/**
 * The daily brief: four sections, one defence block, published once a day.
 *
 * Deliberately separate from InsightPayload rather than an extension of it.
 * The old shape is a flat list of notes ordered by impact; this one is four
 * fixed sections with their own ordering rules, and trying to be both would
 * mean every field is optional and nothing is guaranteed.
 *
 * Roster takes no items at all. The app already has all fourteen players,
 * their projections, ranks and opponents — the only thing it cannot know is
 * how hard the matchup is, and that arrives once in `defense`. So the roster
 * table renders on a day nothing is published, which is the whole point of
 * keeping the deterministic half in the app.
 */
data class DailyBrief(
    val week: Int?,
    val generatedAtMillis: Long?,
    val defense: DefenseGrades,
    val doFirst: List<DoFirstCard>,
    val trades: List<TradeProposal>,
    val candidates: List<Candidate>
) {
    val hoursOld: Long?
        get() = generatedAtMillis?.let { (System.currentTimeMillis() - it) / 3_600_000 }

    /**
     * Cost of waiting, not value.
     *
     * A free claim that clears tonight outranks a lineup call worth more with
     * five days left on it, because the claim stops existing and the lineup
     * call does not. Tier breaks ties; it never leads. Undated cards sink.
     */
    fun doFirstOrdered(now: Long): List<DoFirstCard> = doFirst
        .filterNot { it.isExpired(now) }
        .sortedWith(
            compareBy<DoFirstCard> { it.deadlineMillis ?: Long.MAX_VALUE }
                .thenBy { BriefTier.rank(it.tier) }
        )
        .take(MAX_DO_FIRST)

    companion object {
        /** Overflow falls to the sections below, never to a "show more". */
        const val MAX_DO_FIRST = 4
    }
}

/**
 * How hard the matchup is, as a word.
 *
 * Not a derived rank. Ranking defences by points allowed needs games played,
 * which in week 1 is nothing at all, and by the time the sample is honest the
 * rosters that produced it have changed. A grade is a judgement that can be
 * made from reporting on day one and corrected on day two.
 *
 * Red never means a good start. That inversion is the reason the scale reads
 * from the offence's point of view rather than the defence's.
 */
enum class MatchupGrade(val label: String, val hex: Long) {
    GREAT("Great", 0xFF4FD0B0),
    GOOD("Good", 0xFF7FB0E0),
    AVERAGE("Average", 0xFF8BA0B5),
    SHAKY("Shaky", 0xFFE0A85E),
    POOR("Poor", 0xFFE78E88),

    /** Out, on IR, or on bye. The tile goes neutral and reads Sit. */
    SIT("Sit", 0xFF8BA0B5);

    companion object {
        fun from(s: String?): MatchupGrade? =
            entries.firstOrNull { it.name.equals(s?.trim(), true) }
    }
}

/**
 * Opponent abbreviation to position to grade.
 *
 * Keyed by the DEFENCE being faced, so one block serves the roster, the wire
 * and the candidate list and the same opponent cannot grade two ways on one
 * screen.
 */
class DefenseGrades(private val byTeam: Map<String, Map<String, MatchupGrade>>) {

    /** Null when we have no read, which renders as an empty tile, not Average. */
    fun grade(opponentAbbrev: String?, position: String?): MatchupGrade? {
        if (opponentAbbrev.isNullOrBlank() || position.isNullOrBlank()) return null
        return byTeam[opponentAbbrev.uppercase()]?.get(position.uppercase())
    }

    val isEmpty: Boolean get() = byTeam.isEmpty()

    companion object {
        fun parse(o: JSONObject?): DefenseGrades {
            if (o == null) return DefenseGrades(emptyMap())
            val out = mutableMapOf<String, Map<String, MatchupGrade>>()
            for (team in o.keys()) {
                val pos = o.optJSONObject(team) ?: continue
                val inner = mutableMapOf<String, MatchupGrade>()
                for (p in pos.keys()) {
                    MatchupGrade.from(pos.optString(p))?.let { inner[p.uppercase()] = it }
                }
                if (inner.isNotEmpty()) out[team.uppercase()] = inner
            }
            return DefenseGrades(out)
        }
    }
}

/**
 * The four colour bands for this screen.
 *
 * Named BriefTier because WireTiers.kt already owns `Tier` in this package,
 * and they are genuinely different things: that one classifies a wire player
 * against replacement level, this one is a display scale.
 */
/** Legendary, Elite, Solid, Depth. Carried by border, rail, badge and button only. */
object BriefTier {
    const val LEGENDARY = "LEGENDARY"
    const val ELITE = "ELITE"
    const val SOLID = "SOLID"
    const val DEPTH = "DEPTH"

    private val order = listOf(LEGENDARY, ELITE, SOLID, DEPTH)
    fun rank(s: String?) = order.indexOf(s?.uppercase()).takeIf { it >= 0 } ?: order.size

    fun hex(s: String?): Long = when (s?.uppercase()) {
        LEGENDARY -> 0xFFF0954E
        ELITE -> 0xFFB79CF0
        SOLID -> 0xFF7FB0E0
        else -> 0xFF8BA0B5
    }
}

/** One side of a swap card. `role` is why he matters, in a few words. */
data class CardPlayer(
    val playerId: Int,
    val role: String?,
    /** Red line under the vacating player only: "Out for weeks". */
    val alert: String?
)

data class DoFirstCard(
    /** Stable across days so a dismissal sticks for the session. */
    val id: String,
    val tier: String,
    /** "Salt Flats", "Your bench" — where the move comes from. */
    val source: String?,
    /** FREEAGENT, WAIVERS, ROSTERED. */
    val status: String?,
    val deadlineMillis: Long?,
    val incoming: CardPlayer?,
    /** Null on a housekeeping card. The app hides the arrow. */
    val outgoing: CardPlayer?,
    /** Only when the projections do not explain the move on their own. */
    val why: String?,
    val action: InsightAction?
) {
    fun isExpired(now: Long) = deadlineMillis != null && now >= deadlineMillis
}

/**
 * A two-way trade where both sides gain by their own starting lineups.
 *
 * A proposal that only helps you is not a proposal, it is a wish. The other
 * manager's gain is stated in the headline rather than buried, because hiding
 * it is what makes an offer look lopsided and get declined unread.
 */
data class TradeProposal(
    val id: String,
    val partnerTeamId: Int,
    /** LIKELY, EVEN, LONGSHOT. A read on willingness, never a percentage. */
    val odds: String,
    val youGive: List<Int>,
    val youGet: List<Int>,
    val yourGain: Double,
    val theirGain: Double,
    val headline: String,
    val yourSurplus: String?,
    val theirHole: String?,
    val yourLineup: String?,
    val theirLineup: String?,
    val risk: String?
)

/**
 * Someone worth watching. Nothing here is urgent — the moment it is, it
 * becomes a Do-first card and leaves this list.
 *
 * The note is the reason the row exists, usually an observation that a player
 * with a real role is unrostered when he should not be. It is never
 * truncated.
 */
data class Candidate(
    val playerId: Int,
    val tier: String,
    val note: String
)

/**
 * Parses the published daily brief.
 *
 * Every field is optional and every failure is local: a malformed trade drops
 * that trade, not the screen. The writer will sometimes be ahead of the
 * reader and a brief that renders three of four sections is worth far more
 * than one that renders none.
 */
object DailyBriefParser {

    fun parse(raw: String): DailyBrief? = runCatching {
        val o = JSONObject(raw)

        // Firestore serialises timestamps as {_seconds, _nanoseconds}; the
        // publisher writes ISO-8601. Accept both, and accept millis or not.
        val ts = o.optJSONObject("generatedAt")?.optLong("_seconds")?.times(1000)
            ?: o.optString("generatedAt", "").takeIf { it.isNotBlank() }?.let {
                runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
            }

        DailyBrief(
            week = o.optInt("week", 0).takeIf { it > 0 },
            generatedAtMillis = ts,
            defense = DefenseGrades.parse(o.optJSONObject("defense")),
            doFirst = o.optJSONArray("doFirst").mapObjects { card(it) },
            trades = o.optJSONArray("trades").mapObjects { trade(it) },
            candidates = o.optJSONArray("candidates").mapObjects { candidate(it) }
        )
    }.getOrNull()

    private fun millis(s: String?): Long? = s?.takeIf { it.isNotBlank() }
        ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun player(o: JSONObject?): CardPlayer? {
        val id = o?.optInt("playerId", 0)?.takeIf { it != 0 } ?: return null
        return CardPlayer(
            playerId = id,
            role = o.optString("role", "").ifBlank { null },
            alert = o.optString("alert", "").ifBlank { null }
        )
    }

    private fun card(o: JSONObject): DoFirstCard? {
        val act = o.optJSONObject("action")?.let { a ->
            InsightAction(
                type = a.optString("type", ""),
                playerId = a.optInt("playerId", 0).takeIf { it != 0 },
                dropPlayerId = a.optInt("dropPlayerId", 0).takeIf { it != 0 },
                label = a.optString("label", "").ifBlank { a.optString("type", "Go") }
            ).takeIf { it.type.isNotBlank() }
        }
        val incoming = player(o.optJSONObject("in"))
        val outgoing = player(o.optJSONObject("out"))
        // A card with neither side is nothing to draw.
        if (incoming == null && outgoing == null) return null
        return DoFirstCard(
            id = o.optString("id", "").ifBlank {
                "c-" + (incoming?.playerId ?: outgoing?.playerId ?: 0)
            },
            tier = o.optString("tier", BriefTier.DEPTH),
            source = o.optString("source", "").ifBlank { null },
            status = o.optString("status", "").ifBlank { null },
            deadlineMillis = millis(o.optString("deadline", "")),
            incoming = incoming,
            outgoing = outgoing,
            why = o.optString("why", "").ifBlank { null },
            action = act
        )
    }

    private fun trade(o: JSONObject): TradeProposal? {
        val partner = o.optInt("partnerTeamId", 0).takeIf { it != 0 } ?: return null
        val give = o.optJSONArray("youGive").ints()
        val get = o.optJSONArray("youGet").ints()
        if (give.isEmpty() || get.isEmpty()) return null
        return TradeProposal(
            id = o.optString("id", "").ifBlank { "t-$partner" },
            partnerTeamId = partner,
            odds = o.optString("odds", "EVEN").uppercase(),
            youGive = give,
            youGet = get,
            yourGain = o.optDouble("yourGain", 0.0),
            theirGain = o.optDouble("theirGain", 0.0),
            headline = o.optString("headline", ""),
            yourSurplus = o.optString("yourSurplus", "").ifBlank { null },
            theirHole = o.optString("theirHole", "").ifBlank { null },
            yourLineup = o.optString("yourLineup", "").ifBlank { null },
            theirLineup = o.optString("theirLineup", "").ifBlank { null },
            risk = o.optString("risk", "").ifBlank { null }
        )
    }

    private fun candidate(o: JSONObject): Candidate? {
        val id = o.optInt("playerId", 0).takeIf { it != 0 } ?: return null
        return Candidate(
            playerId = id,
            tier = o.optString("tier", BriefTier.DEPTH),
            note = o.optString("note", "")
        )
    }

    private fun <T> org.json.JSONArray?.mapObjects(f: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        val out = mutableListOf<T>()
        for (i in 0 until length()) {
            optJSONObject(i)?.let { o -> runCatching { f(o) }.getOrNull()?.let(out::add) }
        }
        return out
    }

    private fun org.json.JSONArray?.ints(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optInt(it, 0).takeIf { v -> v != 0 } }
    }
}
