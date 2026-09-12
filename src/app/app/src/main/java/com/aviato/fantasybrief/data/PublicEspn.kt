package com.aviato.fantasybrief.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ESPN's PUBLIC site APIs. No cookies, different hosts, different id space
 * (maybe). These carry depth charts, injury detail and news that the fantasy
 * API does not expose.
 *
 * EVERYTHING here gates on one question: is the fantasy playerId the same
 * number as the site API athlete id? If yes, joining is free. If no, the only
 * option is name matching, which is fragile across suffixes and punctuation.
 */
class PublicEspnClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Result(val code: Int, val body: String, val error: String? = null) {
        val ok: Boolean get() = code in 200..299
    }

    fun get(url: String): Result = try {
        // No Cookie header — sending fantasy credentials to a different
        // ESPN host would leak them for no benefit.
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "FantasyBrief/1.0")
            .build()
        http.newCall(request).execute().use { r ->
            Result(r.code, r.body?.string().orEmpty())
        }
    } catch (e: Exception) {
        Result(-1, "", e.message ?: e.toString())
    }

    fun athlete(athleteId: Int) = get(
        "$SITE/apis/common/v3/sports/football/nfl/athletes/$athleteId"
    )

    fun depthChart(proTeamId: Int, season: Int) = get(
        "$CORE/v2/sports/football/leagues/nfl/seasons/$season/teams/" +
            "$proTeamId/depthcharts"
    )

    fun teamInjuries(proTeamId: Int) = get(
        "$SITE/apis/site/v2/sports/football/nfl/teams/$proTeamId/injuries"
    )

    companion object {
        const val SITE = "https://site.api.espn.com"
        const val CORE = "https://sports.core.api.espn.com"
    }
}

/**
 * Answers the ID-join question against players we already have names for,
 * so a match or mismatch is self-evident rather than requiring a lookup.
 */
object IdJoinProbe {

    fun run(league: League, season: Int): String {
        val client = PublicEspnClient()
        val sb = StringBuilder()

        val sample = league.teams
            .flatMap { it.roster }
            .filter { it.playerId > 0 }
            .sortedByDescending { it.projection ?: 0.0 }
            .take(6)

        sb.append("ID JOIN PROBE\n")
        sb.append("Does fantasy playerId == site API athlete id?\n\n")

        var matches = 0
        sample.forEach { p ->
            val res = client.athlete(p.playerId)
            sb.append("fantasy ").append(p.playerId).append("  ")
                .append(p.name).append("\n")

            if (!res.ok) {
                sb.append("   HTTP ").append(res.code)
                res.error?.let { sb.append("  ").append(it) }
                sb.append("\n\n")
                return@forEach
            }

            val returned = runCatching {
                val root = JSONObject(res.body)
                val athlete = root.optJSONObject("athlete") ?: root
                athlete.optString("displayName", "")
                    .ifBlank { athlete.optString("fullName", "") }
            }.getOrDefault("")

            val same = returned.isNotBlank() &&
                returned.lowercase().take(6) == p.name.lowercase().take(6)
            if (same) matches++

            sb.append("   site returned: ")
                .append(returned.ifBlank { "(no name field)" })
                .append(if (same) "   MATCH" else "   MISMATCH")
                .append("\n\n")
        }

        sb.append("=== ").append(matches).append(" of ").append(sample.size)
            .append(" matched ===\n")
        sb.append(
            if (matches == sample.size)
                "IDs share a namespace. Depth charts, injuries and news can be\n" +
                    "joined directly on playerId. Build them."
            else if (matches == 0)
                "Different id spaces. Any join needs name matching, which is\n" +
                    "fragile across suffixes (Sr./Jr./III) and punctuation."
            else
                "Partial. Something is off — check the mismatched rows before\n" +
                    "building anything on this."
        ).append("\n\n")

        // The depth chart is the prize: it separates "bad player" from
        // "buried player", which one projection number cannot.
        val team = sample.firstOrNull()?.proTeamId ?: 0
        if (team > 0) {
            val dc = client.depthChart(team, season)
            sb.append("DEPTH CHART proTeamId ").append(team)
                .append(" — HTTP ").append(dc.code)
                .append(", ").append(dc.body.length).append(" chars\n\n")
            if (dc.ok) sb.append(summarizeDepthChart(dc.body))
            else dc.error?.let { sb.append(it).append("\n") }
            sb.append("\n")
        }

        // Two candidate injury paths — the site one returned an empty array,
        // which may mean "no injuries" or "wrong path".
        listOf(
            "site" to client.teamInjuries(team),
            "core" to client.get(
                "${PublicEspnClient.CORE}/v2/sports/football/leagues/nfl/" +
                    "teams/$team/injuries"
            )
        ).forEach { (label, res) ->
            sb.append("INJURIES [").append(label).append("] HTTP ")
                .append(res.code).append(", ").append(res.body.length)
                .append(" chars\n")
            if (res.ok && res.body.length > 10) {
                sb.append(JsonProbe.outline(res.body, 5)).append("\n")
            } else {
                sb.append(res.body.take(120)).append("\n\n")
            }
        }

        return sb.toString()
    }

    /**
     * Prints the OFFENSIVE unit only, flattened.
     *
     * The response holds three units and the defensive formation sorts first,
     * so a generic outline shows cornerbacks and never reaches the running
     * backs. Athletes are $ref links rather than inline records; the athlete
     * id is embedded in the URL, so it can be pulled from the string instead
     * of costing one request per player.
     */
    private fun summarizeDepthChart(raw: String): String {
        val sb = StringBuilder()
        val items = JSONObject(raw).optJSONArray("items") ?: return "(no items)\n"

        for (i in 0 until items.length()) {
            val unit = items.optJSONObject(i) ?: continue
            val name = unit.optString("name", "unit $i")
            val positions = unit.optJSONObject("positions") ?: continue

            // Skip defense and special teams; fantasy only needs the offense.
            val keys = positions.keys().asSequence().toList()
            val offensive = keys.filter {
                it in setOf("qb", "rb", "wr", "te", "lwr", "rwr", "swr", "fb")
            }
            if (offensive.isEmpty()) {
                sb.append("-- ").append(name).append("  (skipped: ")
                    .append(keys.size).append(" non-offensive slots)\n")
                continue
            }

            sb.append("-- ").append(name).append("\n")
            offensive.forEach { slot ->
                val group = positions.optJSONObject(slot) ?: return@forEach
                val athletes = group.optJSONArray("athletes") ?: return@forEach
                sb.append("   ").append(slot.uppercase()).append("\n")
                for (a in 0 until athletes.length()) {
                    val entry = athletes.optJSONObject(a) ?: continue
                    val rank = entry.optInt("rank", -1)
                    val ref = entry.optJSONObject("athlete")?.optString("\u0024ref", "")
                        ?: entry.optString("athlete", "")
                    val id = Regex("athletes/(\\d+)").find(ref)?.groupValues?.get(1)
                    sb.append("      rank ").append(rank)
                        .append("  athleteId ").append(id ?: "?")
                        .append("  slot ").append(entry.opt("slot"))
                        .append("\n")
                }
            }
        }
        return sb.toString()
    }
}
