package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the app claimed, so it can be checked later.
 *
 * Every recommendation this app has made so far vanished when the screen was
 * closed. Which means there is no evidence that ownership velocity beats
 * reading the news, that PROJ-STALE finds anything real, or that the tier
 * thresholds are tuned correctly. A prediction that is never scored is an
 * opinion.
 *
 * Recording is deliberately cheap and automatic — asking you to log decisions
 * would guarantee it stops after two weeks.
 */
data class Prediction(
    val id: String,
    val leagueId: Long,
    val season: Int,
    /** The scoring period this was made in. */
    val week: Int,
    val madeAtMillis: Long,
    /** WIRE_TIER, PROJ_STALE, BENEFICIARY, DROP_CANDIDATE, START_SIT */
    val kind: String,
    val subjectId: Int,
    val subjectName: String,
    /** The player being compared against — the drop, or the benched starter. */
    val comparedToId: Int?,
    val comparedToName: String?,
    /** What the app believed at the time. */
    val claimedEdge: Double,
    val subjectProjection: Double,
    val comparedProjection: Double?,
    val note: String
) {
    fun key() = "$leagueId:$week:$kind:$subjectId:${comparedToId ?: 0}"
}

/**
 * A settled prediction, once real points exist for that week.
 */
data class Verdict(
    val prediction: Prediction,
    val subjectActual: Double,
    val comparedActual: Double?,
    /** Positive means the app was right about the direction. */
    val realEdge: Double,
    val correct: Boolean,
    /**
     * Did you act on it? Derived from your roster and the transaction log,
     * never asked — a question you have to answer is one you stop answering.
     */
    val followed: Boolean
) {
    /**
     * Points forgone by declining a recommendation that turned out right.
     * Zero when followed, or when the app was wrong — you cannot lose points
     * by ignoring bad advice.
     */
    val pointsLeftOnTable: Double
        get() = if (!followed && correct && realEdge > 0) realEdge else 0.0

    /** Points saved by declining a recommendation that turned out wrong. */
    val pointsSavedByIgnoring: Double
        get() = if (!followed && !correct && realEdge < 0) -realEdge else 0.0
}

class PredictionStore(context: Context) {

    private val dir = context.filesDir

    private fun file(leagueId: Long, season: Int) =
        File(dir, "predictions_${leagueId}_$season.json")

    fun all(leagueId: Long, season: Int): List<Prediction> {
        val f = file(leagueId, season)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Prediction(
                            id = o.optString("id"),
                            leagueId = leagueId,
                            season = season,
                            week = o.optInt("w"),
                            madeAtMillis = o.optLong("t"),
                            kind = o.optString("k"),
                            subjectId = o.optInt("s"),
                            subjectName = o.optString("sn"),
                            comparedToId = if (o.isNull("c")) null else o.optInt("c"),
                            comparedToName = o.optString("cn", "").ifBlank { null },
                            claimedEdge = o.optDouble("e", 0.0),
                            subjectProjection = o.optDouble("sp", 0.0),
                            comparedProjection = if (o.isNull("cp")) null
                                                 else o.optDouble("cp"),
                            note = o.optString("n", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Append-only, deduplicated by (league, week, kind, players).
     *
     * The same recommendation shown on five refreshes in one week is ONE
     * prediction, not five — otherwise the scorecard would measure how often
     * you opened the app.
     */
    fun record(leagueId: Long, season: Int, incoming: List<Prediction>) {
        if (incoming.isEmpty()) return
        val existing = all(leagueId, season)
        val known = existing.map { it.key() }.toSet()
        val fresh = incoming.filter { it.key() !in known }
        if (fresh.isEmpty()) return

        val arr = JSONArray()
        (existing + fresh).forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id); put("w", p.week); put("t", p.madeAtMillis)
                    put("k", p.kind); put("s", p.subjectId); put("sn", p.subjectName)
                    p.comparedToId?.let { put("c", it) }
                    p.comparedToName?.let { put("cn", it) }
                    put("e", p.claimedEdge); put("sp", p.subjectProjection)
                    p.comparedProjection?.let { put("cp", it) }
                    put("n", p.note)
                }
            )
        }
        file(leagueId, season).writeText(arr.toString())
    }

    fun clear(leagueId: Long, season: Int) {
        file(leagueId, season).delete()
    }
}

/**
 * Turns predictions into verdicts once the week's actual points exist.
 *
 * Only settles weeks that are FULLY PLAYED. Scoring a prediction mid-week
 * would grade a player who has not taken the field yet, which would make the
 * scorecard say whatever the schedule happened to be.
 */
object Scorecard {

    data class Summary(
        val kind: String,
        val settled: Int,
        val correct: Int,
        val followed: Int,
        val meanEdgeClaimed: Double,
        val meanEdgeReal: Double,
        /** Realised edge on correct calls you declined. */
        val leftOnTable: Double,
        /** Realised loss avoided by declining wrong calls. */
        val savedByIgnoring: Double
    ) {
        val hitRate: Double get() = if (settled == 0) 0.0 else correct.toDouble() / settled
        /** Positive means the app claimed less edge than it delivered. */
        val calibration: Double get() = meanEdgeReal - meanEdgeClaimed
    }

    /**
     * @param actedOn player ids you ended up rostering or starting, per week.
     *   Derived from the event log and roster history by the caller.
     */
    fun settle(
        predictions: List<Prediction>,
        actualsByWeek: Map<Int, Map<Int, Double>>,
        actedOn: Set<Int>,
        currentWeek: Int
    ): List<Verdict> = predictions.mapNotNull { p ->
        // A week is only scoreable once it is behind us.
        if (p.week >= currentWeek) return@mapNotNull null
        val week = actualsByWeek[p.week] ?: return@mapNotNull null
        val subject = week[p.subjectId] ?: return@mapNotNull null
        val compared = p.comparedToId?.let { week[it] }

        val realEdge = if (compared != null) subject - compared else subject
        Verdict(
            prediction = p,
            subjectActual = subject,
            comparedActual = compared,
            realEdge = realEdge,
            // "Correct" means the direction held, not that the number matched.
            correct = if (compared != null) realEdge > 0
                      else subject >= p.subjectProjection,
            followed = p.subjectId in actedOn
        )
    }

    fun summarise(verdicts: List<Verdict>): List<Summary> =
        verdicts.groupBy { it.prediction.kind }.map { (kind, list) ->
            Summary(
                kind = kind,
                settled = list.size,
                correct = list.count { it.correct },
                followed = list.count { it.followed },
                meanEdgeClaimed = list.map { it.prediction.claimedEdge }.average(),
                meanEdgeReal = list.map { it.realEdge }.average(),
                leftOnTable = list.sumOf { it.pointsLeftOnTable },
                savedByIgnoring = list.sumOf { it.pointsSavedByIgnoring }
            )
        }.sortedByDescending { it.settled }

    /**
     * Every player you rostered or started, from the event log plus the
     * current roster. Used to decide whether a recommendation was followed.
     *
     * Imperfect on purpose: a player added and dropped between two refreshes
     * still shows in the activity feed, and a recommendation you acted on for
     * unrelated reasons counts as followed. Both are acceptable — this
     * measures behaviour, not intent.
     */
    fun actedOnIds(league: League, transactions: List<Transaction>): Set<Int> =
        buildSet {
            league.myTeam?.roster?.forEach { add(it.playerId) }
            transactions
                .filter { it.teamId == league.myTeamId }
                .filter { it.kind == TxKind.ADD || it.kind == TxKind.WAIVER_ADD ||
                    it.kind == TxKind.TRADE }
                .forEach { add(it.playerId) }
        }
}
