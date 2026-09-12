package com.aviato.fantasybrief.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ships the daily dump to the analysis endpoint.
 *
 * Fire and forget: a refresh must never wait on this, and a failure here
 * costs nothing that a later refresh will not fix. Throttled because the app
 * refreshes far more often than the underlying league actually changes.
 */
class BriefUploader(context: Context) {

    private val secrets = SecretStore(context.applicationContext)
    private val api = InsightsApi(secrets)
    private val lastSent = mutableMapOf<Long, Long>()

    suspend fun maybeUpload(brief: Brief, live: LiveScoreboard? = null) {
        if (!secrets.hasApi) return
        val leagueId = brief.league.id
        val now = System.currentTimeMillis()
        if (now - (lastSent[leagueId] ?: 0L) < THROTTLE) return

        val week = brief.league.settings.currentMatchupPeriod
        val team = brief.league.myTeam?.name.orEmpty()
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val a = api.uploadBrief(
                    leagueId = leagueId,
                    dump = DumpBuilder2.daily(brief) + liveBlock(brief, live),
                    week = week, teamName = team
                )
                // Same league, different document, so both survive.
                val b = api.uploadBrief(
                    leagueId = leagueId,
                    dump = DumpBuilder2.weekly(brief) + liveBlock(brief, live),
                    week = week, teamName = team, kind = "weekly"
                )
                a && b
            }.getOrDefault(false)
        }
        if (ok) lastSent[leagueId] = now
    }

    /**
     * ESPN's own live numbers, appended after the dump is built.
     *
     * DumpBuilder2 only sees a Brief, and the live scoreboard is fetched
     * separately — so the dump's matchup line was a sum of pregame
     * projections while the app displayed the live figure. Appending here
     * beats threading the scoreboard through every dump section.
     */
    private fun liveBlock(brief: Brief, live: LiveScoreboard?): String {
        if (live == null) return ""
        val myId = brief.league.myTeamId ?: return ""
        val m = brief.matchups.firstOrNull {
            it.homeTeamId == myId || it.awayTeamId == myId
        } ?: return ""
        val oppId = if (m.homeTeamId == myId) m.awayTeamId else m.homeTeamId
        val opp = brief.league.teams.firstOrNull { it.id == oppId }

        return buildString {
            append("\n== LIVE (ESPN's own numbers, overrides the projections above) ==\n")
            append("me   scored ").append(fmt(live.total(myId)))
            append("  projected final ").append(fmt(live.projectedLive(myId)))
            live.winProbability(myId)?.let {
                append("  win prob ").append((it * 100).toInt()).append("%")
            }
            append("\n")
            append("them scored ").append(fmt(live.total(oppId)))
            append("  projected final ").append(fmt(live.projectedLive(oppId)))
            append("   (").append(opp?.name ?: "?").append(")\n")
        }
    }

    private fun fmt(v: Double?) =
        v?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"

    private companion object {
        const val THROTTLE = 15L * 60 * 1000
    }
}
