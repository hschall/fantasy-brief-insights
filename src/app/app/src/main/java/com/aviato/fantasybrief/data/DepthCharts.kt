package com.aviato.fantasybrief.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

data class DepthEntry(
    val athleteId: Int,
    val rank: Int,
    val slot: String        // "rb", "wr", "te", "qb"
)

/**
 * Who is actually ahead of whom, per NFL team.
 *
 * This is the thing a projection number cannot say. A 0.1 projection on a
 * player who is RB1 on his depth chart means the projection is stale; the
 * same number on an RB4 means he is buried. Distinguishing those two was
 * previously only possible with a web search.
 *
 * Verified 2026-09-05 against Detroit: RB rank 1 = athleteId 4429795 =
 * Jahmyr Gibbs, identical to the fantasy playerId.
 */
class DepthCharts(private val byTeam: Map<Int, List<DepthEntry>>) {

    val teamsLoaded: Int get() = byTeam.size

    fun entriesFor(proTeamId: Int): List<DepthEntry> = byTeam[proTeamId].orEmpty()

    /** Offensive chart for one team, grouped by slot and ranked. */
    fun chartFor(proTeamId: Int): Map<String, List<DepthEntry>> =
        byTeam[proTeamId].orEmpty().groupBy { it.slot }
            .mapValues { (_, v) -> v.sortedBy { it.rank } }

    fun rank(proTeamId: Int, playerId: Int): Int? =
        byTeam[proTeamId]?.firstOrNull { it.athleteId == playerId }?.rank

    /** How many players sit at this player's position on his team. */
    fun depthAtPosition(proTeamId: Int, playerId: Int): Int? {
        val entries = byTeam[proTeamId] ?: return null
        val slot = entries.firstOrNull { it.athleteId == playerId }?.slot ?: return null
        return entries.count { it.slot == slot }
    }

    /** The player immediately behind this one — the real handcuff. */
    fun backupTo(proTeamId: Int, playerId: Int): Int? {
        val entries = byTeam[proTeamId] ?: return null
        val target = entries.firstOrNull { it.athleteId == playerId } ?: return null
        return entries
            .filter { it.slot == target.slot && it.rank == target.rank + 1 }
            .minByOrNull { it.rank }?.athleteId
    }

    /**
     * The highest-ranked OTHER player at this player's slot.
     *
     * `backupTo` asked for rank+1, which fails whenever the unavailable
     * player isn't the starter — Josh Jacobs sits at RB4 on Green Bay's
     * chart, so rank 5 found nobody and the whole section came back empty.
     * What's wanted is "who tops this depth chart now", which is rank 1
     * unless rank 1 is the player himself.
     */
    fun bestBehind(proTeamId: Int, playerId: Int): Int? {
        val entries = byTeam[proTeamId] ?: return null
        val target = entries.firstOrNull { it.athleteId == playerId } ?: return null
        return entries
            .filter { it.slot == target.slot && it.athleteId != playerId }
            .minByOrNull { it.rank }?.athleteId
    }

    /** The starter this player backs up, if he is rank 2 or lower. */
    fun startsAheadOf(proTeamId: Int, playerId: Int): Int? {
        val entries = byTeam[proTeamId] ?: return null
        val target = entries.firstOrNull { it.athleteId == playerId } ?: return null
        if (target.rank <= 1) return null
        return entries
            .filter { it.slot == target.slot && it.rank < target.rank }
            .maxByOrNull { it.rank }?.athleteId
    }

    fun label(proTeamId: Int, playerId: Int): String? {
        val r = rank(proTeamId, playerId) ?: return null
        val slot = byTeam[proTeamId]
            ?.firstOrNull { it.athleteId == playerId }?.slot?.uppercase() ?: return null
        return "$slot$r"
    }

    companion object {
        val EMPTY = DepthCharts(emptyMap())

        // Slot keys that matter for fantasy. Teams name their offensive unit
        // differently ("3WR 1TE", "11 Personnel"), so find it by the slots it
        // contains rather than by name or index — the defensive unit sorts
        // first and a naive index lookup returns cornerbacks.
        private val OFFENSIVE = setOf("qb", "rb", "wr", "te", "lwr", "rwr", "swr", "fb")

        fun parse(raw: String): List<DepthEntry> {
            val items = JSONObject(raw).optJSONArray("items") ?: return emptyList()
            val out = mutableListOf<DepthEntry>()

            for (i in 0 until items.length()) {
                val positions = items.optJSONObject(i)?.optJSONObject("positions")
                    ?: continue
                positions.keys().forEach { key ->
                    val slot = key.lowercase()
                    if (slot !in OFFENSIVE) return@forEach
                    val athletes = positions.optJSONObject(key)
                        ?.optJSONArray("athletes") ?: return@forEach

                    for (a in 0 until athletes.length()) {
                        val entry = athletes.optJSONObject(a) ?: continue
                        // Athletes are $ref URLs, not inline records. The id
                        // is in the URL, so parsing the string avoids one
                        // request per player.
                        val ref = entry.optJSONObject("athlete")?.optString("\u0024ref", "")
                            ?: ""
                        val id = Regex("athletes/(\\d+)").find(ref)
                            ?.groupValues?.get(1)?.toIntOrNull() ?: continue
                        // Normalise the WR variants so lwr/rwr/swr compare.
                        val normalised = if (slot.endsWith("wr")) "wr" else slot
                        out.add(DepthEntry(id, entry.optInt("rank", 99), normalised))
                    }
                }
            }
            return out
        }
    }
}

/**
 * Fetches depth charts for the NFL teams a league actually rosters, one
 * request each, cached for the process lifetime. Depth charts move slowly;
 * refetching them on every refresh would triple the request count for data
 * that changes weekly at most.
 */
object DepthChartLoader {

    private val memory = mutableMapOf<String, DepthCharts>()

    /**
     * Thirty-two requests, now IN PARALLEL and backed by disk.
     *
     * Sequential and memory-only, this was the single largest cost of a cold
     * start — every launch paid ~32 round trips one after another. Depth
     * charts change weekly at most, so a 24h disk TTL is generous and the
     * parallel fan-out makes even a cache miss bearable.
     */
    suspend fun load(
        season: Int,
        proTeamIds: Set<Int>,
        cache: ResponseCache
    ): Pair<DepthCharts, String?> = coroutineScope {
        val key = "$season:${proTeamIds.sorted().joinToString(",")}"
        memory[key]?.let { return@coroutineScope it to null }

        val client = PublicEspnClient()
        val ids = proTeamIds.filter { it > 0 }

        val results = ids.map { teamId ->
            async(Dispatchers.IO) {
                val ck = "depth_${season}_$teamId"
                val body = cache.get(ck, ResponseCache.TTL_DAY)
                    ?: client.depthChart(teamId, season)
                        .takeIf { it.ok }?.body?.also { cache.put(ck, it) }
                teamId to (body?.let {
                    runCatching { DepthCharts.parse(it) }.getOrDefault(emptyList())
                } ?: emptyList())
            }
        }.map { it.await() }

        val byTeam = results.filter { it.second.isNotEmpty() }.toMap()
        val failures = results.count { it.second.isEmpty() }

        val charts = DepthCharts(byTeam)
        val note = when {
            byTeam.isEmpty() -> "Depth charts unavailable"
            // A couple of teams failing does not change any answer this app
            // gives, and a note that never clears is one you stop reading.
            failures > 4 -> "Depth charts missing for $failures teams"
            else -> null
        }
        if (byTeam.isNotEmpty()) memory[key] = charts
        charts to note
    }
}
