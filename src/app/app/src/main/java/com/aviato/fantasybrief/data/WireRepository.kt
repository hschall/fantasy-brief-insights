package com.aviato.fantasybrief.data

/**
 * Fetches the free agent pool and sorts it by ownership VELOCITY.
 *
 * ESPN has no sortPercOwnedChange key, so sorting must happen here. And a
 * single batch sorted by percentOwned descending misses the tail where the
 * money signal lives — a 4%-owned player rising fast is not in the top 200
 * by ownership. So we make two passes and merge.
 */
class WireRepository(private val client: EspnClient) {

    sealed interface Outcome {
        data class Ok(
            val players: List<WirePlayer>,
            val note: String?
        ) : Outcome
        data class Failed(val code: Int, val message: String) : Outcome
    }

    fun load(season: Int, leagueId: Long, scoringPeriod: Int): Outcome {
        val url = client.leagueUrl(season, leagueId, "kona_player_info")

        val byOwnership = client.get(url, filter(sortKey = "sortPercOwned", limit = 250))
        if (!byOwnership.ok) {
            return Outcome.Failed(
                byOwnership.code,
                when (byOwnership.code) {
                    401 -> "Session expired — log in again."
                    // Per your spec: a limit with no valid sort returns this.
                    400 -> "400 — filter rejected. ${byOwnership.body.take(200)}"
                    else -> "HTTP ${byOwnership.code}"
                }
            )
        }

        val primary = try {
            WireParser.parse(byOwnership.body, scoringPeriod)
        } catch (e: Exception) {
            return Outcome.Failed(-2, "Wire parse failed: ${e.message}")
        }

        // Second pass reaching into the low-ownership tail. Best effort:
        // if ESPN rejects this sort key we still have the primary batch.
        var note: String? = null
        val tail = run {
            val res = client.get(url, filter(sortKey = "sortPercOwned", limit = 250, asc = true))
            if (!res.ok) {
                note = "Tail pass unavailable (HTTP ${res.code}) — deep sleepers may be missing"
                emptyList()
            } else {
                try {
                    WireParser.parse(res.body, scoringPeriod)
                } catch (e: Exception) {
                    note = "Tail pass parse failed"
                    emptyList()
                }
            }
        }

        val merged = (primary + tail)
            .distinctBy { it.playerId }
            .sortedWith(
                compareByDescending<WirePlayer> { it.percentChange }
                    .thenByDescending { it.projection ?: 0.0 }
            )

        return Outcome.Ok(merged, note)
    }

    /**
     * Ownership for ALL players, rostered included.
     *
     * mRoster's player objects carry no `ownership` key — verified by probe —
     * and the wire query filters to FREEAGENT/WAIVERS, which excludes rostered
     * players by definition. So drop filterStatus entirely and pull wide by
     * ownership. Uses only `limit` + `sortPercOwned`, both already proven; no
     * unverified filter syntax.
     */
    /**
     * The whole available pool, for browsing.
     *
     * The tiered view answers "what should I do"; this answers "what is out
     * there", which is a different question and needs breadth.
     *
     * 150, sorted by ownership descending. A ten-team league rosters ~140
     * players; beyond the top 150 available you are into people who have never
     * had a fantasy-relevant snap, and nobody browses to find them.
     */
    fun loadPool(season: Int, leagueId: Long, scoringPeriod: Int): List<WirePlayer> {
        val url = client.leagueUrl(season, leagueId, "kona_player_info")
        val filter =
            """{"players":{"filterStatus":{"value":["FREEAGENT","WAIVERS"]},""" +
                """"limit":150,"sortPercOwned":{"sortAsc":false,"sortPriority":1}}}"""
        val res = client.get(url, filter)
        if (!res.ok) {
            lastPoolError = if (res.code == -1)
                "request never completed: ${res.error ?: "timeout or no network"}"
            else "HTTP ${res.code} ${res.body.take(160)}"
            return emptyList()
        }
        val parsed = runCatching { WireParser.parse(res.body, scoringPeriod) }
            .getOrElse {
                lastPoolError = "parse failed: ${it.message}"
                emptyList()
            }
        if (parsed.isEmpty()) lastPoolError = "0 players from ${res.body.length} chars"
        else lastPoolError = null
        return parsed
    }

    /** Set when loadPool comes back empty, so the UI can say why. */
    var lastPoolError: String? = null
        private set

    /** Everything from one fetch: market map, tiered-wire input, browse pool. */
    data class Snapshot(
        val market: Map<Int, WirePlayer>,
        val available: List<WirePlayer>
    ) {
        val pool: List<WirePlayer>
            get() = available.sortedByDescending { it.percentOwned }.take(150)
    }

    /**
     * ONE request replaces four.
     *
     * The unfiltered pull is a strict superset of the filtered ones — an
     * available player is a market player whose status is FREEAGENT or
     * WAIVERS. At 500 wide, with ~140 rostered in a ten-team league, the
     * available tail runs 360 deep sorted by ownership; anyone with a
     * meaningful delta is inside that, so the ascending second pass that
     * existed to catch low-owned risers is no longer needed either.
     */
    fun loadAll(season: Int, leagueId: Long, scoringPeriod: Int): Snapshot? {
        val url = client.leagueUrl(season, leagueId, "kona_player_info")
        val filter =
            """{"players":{"limit":500,"sortPercOwned":{"sortAsc":false,"sortPriority":1}}}"""
        val res = client.get(url, filter)
        if (!res.ok) {
            lastPoolError = if (res.code == -1)
                "request never completed: ${res.error ?: "timeout or no network"}"
            else "HTTP ${res.code} ${res.body.take(160)}"
            return null
        }
        val all = runCatching { WireParser.parse(res.body, scoringPeriod) }
            .getOrElse { lastPoolError = "parse failed: ${it.message}"; return null }
        lastPoolError = null
        val available = all.filter { it.status == "FREEAGENT" || it.status == "WAIVERS" }
        return Snapshot(
            market = all.associateBy { it.playerId },
            available = available
        )
    }

    fun loadMarket(season: Int, leagueId: Long, scoringPeriod: Int): Map<Int, WirePlayer> {
        val url = client.leagueUrl(season, leagueId, "kona_player_info")
        val filter =
            """{"players":{"limit":400,"sortPercOwned":{"sortAsc":false,"sortPriority":1}}}"""
        val res = client.get(url, filter)
        if (!res.ok) return emptyMap()
        return try {
            WireParser.parse(res.body, scoringPeriod).associateBy { it.playerId }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * The x-fantasy-filter HEADER — never a query param.
     * A `limit` without a valid `sort` returns 400.
     */
    private fun filter(sortKey: String, limit: Int, asc: Boolean = false): String =
        """{"players":{"filterStatus":{"value":["FREEAGENT","WAIVERS"]},""" +
            """"limit":$limit,"$sortKey":{"sortAsc":$asc,"sortPriority":1}}}"""
}
