package com.aviato.fantasybrief.data

/**
 * Two calls: the league (teams, rosters, settings) and the season-level
 * pro team schedules. The latter is cached for the process lifetime because
 * the NFL schedule does not change during a season.
 */
class LeagueRepository(
    private val client: EspnClient,
    private val secrets: SecretStore,
    private val diskCache: ResponseCache? = null
) {

    sealed interface Outcome {
        data class Ok(
            val rawLeagueBody: String,
            val league: League,
            val proTeams: ProTeamIndex,
            val millis: Long,
            val bytes: Int,
            val scheduleNote: String?
        ) : Outcome

        data class Failed(val code: Int, val message: String) : Outcome
    }

    fun load(season: Int, leagueId: Long): Outcome {
        val url = client.leagueUrl(season, leagueId, "mTeam", "mRoster", "mSettings")
        val result = client.get(url)

        if (!result.ok) {
            return Outcome.Failed(
                result.code,
                when (result.code) {
                    401 -> "Session expired — log in again."
                    404 -> "No league found with id $leagueId in $season."
                    -1 -> result.error ?: "Network unreachable."
                    else -> "HTTP ${result.code}. ${result.body.take(200)}"
                }
            )
        }

        val league = try {
            LeagueParser.parse(result.body, leagueId, season, secrets.swid)
        } catch (e: Exception) {
            return Outcome.Failed(-2, "Parse failed: ${e.message}")
        }

        // Schedule failure degrades the rows, it does not fail the load.
        // Byes missing is annoying; no roster at all is useless.
        val (index, note) = loadProTeams(season)
        return Outcome.Ok(result.body, league, index, result.millis,
            result.body.length, note)
    }

    private fun loadProTeams(season: Int): Pair<ProTeamIndex, String?> {
        memo[season]?.let { return it to null }

        // NOTE: no /segments in this path. Adding it returns nothing useful.
        // Bye weeks and fixtures do not change in-season, so this is cached to
        // disk for a week — one of the easiest wins on cold-start time.
        val cacheKey = "proteams_$season"
        val cached = diskCache?.get(cacheKey, ResponseCache.TTL_SEASON)
        val result = if (cached != null) EspnClient.Result(200, cached)
                     else client.get(client.seasonUrl(season, "proTeamSchedules_wl"))
        if (!result.ok) {
            return ProTeamIndex.EMPTY to "Schedules unavailable (HTTP ${result.code})"
        }
        if (cached == null) diskCache?.put(cacheKey, result.body)
        return try {
            val index = ProTeamIndex.parse(result.body)
            if (index.size < 30) {
                // 32 expected. Fewer means the shape moved — say so loudly.
                index to "Only ${index.size} pro teams parsed — probe proTeamSchedules_wl"
            } else {
                memo[season] = index
                index to null
            }
        } catch (e: Exception) {
            ProTeamIndex.EMPTY to "Schedule parse failed: ${e.message}"
        }
    }

    private companion object {
        // Renamed: the constructor now takes a `cache` too.
        val memo = mutableMapOf<Int, ProTeamIndex>()
    }
}
