package com.aviato.fantasybrief.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Everything one refresh produces. */
data class Brief(
    val league: League,
    val proTeams: ProTeamIndex,
    val depth: DepthCharts,
    val wire: List<WirePlayer>,
    val flags: List<ResearchFlag>,
    val replacement: ReplacementLevel,
    val dropCandidates: List<DropCandidate>,
    val shapes: List<TeamShape>,
    val beneficiaries: List<Beneficiary>,
    val trends: Map<Int, Trend>,
    val tiers: Map<Tier, List<TieredPlayer>>,
    val pool: List<WirePlayer>,
    val breakouts: List<Breakout>,
    val atRisk: List<AtRiskPair>,
    /** playerId -> analyst consensus. Covers rostered players too, since
     *  the wire fetch is unfiltered. */
    val rankings: Map<Int, PlayerRanking>,
    val depthMoves: List<DepthMove>,
    val depthObservations: Int,
    val matchups: List<Matchup>,
    val schedule: List<Matchup>,
    val standings: List<StandingRow>,
    val powerSeries: Map<Int, PowerSeries>,
    val powerChartable: Boolean,
    val archivedWeeks: List<Int>,
    val weekArchives: Map<Int, WeekArchive>,
    val predictions: List<Prediction>,
    val verdicts: List<Verdict>,
    val scorecard: List<Scorecard.Summary>,
    val pending: List<PendingClaim>,
    val transactions: List<Transaction>,
    val newTransactions: List<Transaction>,
    val playerNames: Map<Int, String>,
    val previousSnapshotAgeHours: Long?,
    val baselineAgeMinutes: Long?,
    val runCount: Int,
    val notes: List<String>,
    val millis: Long,
    /** When this data was fetched. Distinct from the diff window. */
    val fetchedAtMillis: Long = System.currentTimeMillis()
) {

    /**
     * Display tier: the projection tier, promoted one step when the analysts
     * rank him inside half the startable pool at his position.
     *
     * DISPLAY ONLY. The decision paths — drop candidates, team shape, the
     * gold-star alarm, wire tiers — deliberately still read
     * replacement.rank() on projection alone. Promotion changes what you SEE;
     * changing what the app ALERTS on is a separate decision with
     * consequences at one in the morning.
     */
    fun tierOf(position: String, projection: Double?, playerId: Int): TierVerdict =
        Tiers.forPlayer(league, replacement, position, projection, rankings[playerId])

    fun tierNameOf(position: String, projection: Double?, playerId: Int): String =
        tierOf(position, projection, playerId).tier
}

/**
 * One refresh, assembled in dependency order.
 *
 * THE ORDER BELOW IS LOAD-BEARING and has broken this file repeatedly:
 *   1. league state            everything needs it
 *   2. wire + market data      enriches the league with ownership
 *   3. depth charts            needs the set of NFL teams rostered
 *   4. derived views           replacement, drops, shapes, beneficiaries, tiers
 *   5. matchups + pending      independent, cheap
 *   6. events                  transactions from ESPN's permanent feed
 *   7. observations            windowed diff, THEN record
 *
 * Step 7 must come last. Recording before diffing compares a run against
 * itself and finds nothing, forever.
 */
class BriefRepository(context: Context, private val store: SecretStore) {

    private val client = EspnClient(store)
    private val cache = ResponseCache(context.applicationContext)
    private val leagueRepo = LeagueRepository(client, store, diskCache = cache)
    private val wireRepo = WireRepository(client)
    private val snapshots = ObservationStore(context.applicationContext)
    private val events = EventStore(context.applicationContext)
    private val powerHistory = PowerHistory(context.applicationContext)
    private val depthHistory = DepthHistory(context.applicationContext)
    private val predictions = PredictionStore(context.applicationContext)
    private val weekArchive = WeekArchiveStore(context.applicationContext)
    private val rankHistory = RankHistory(context.applicationContext)
    private var analystError: String? = null

    sealed interface Outcome {
        data class Ok(val brief: Brief) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * @param onProgress called with (fraction 0..1, label) as phases complete.
     *   Real progress, not a timer — each phase is a fixed share of the work,
     *   so a bar driven by this actually reflects how far along the fetch is.
     */
    suspend fun load(
        season: Int,
        leagueId: Long,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Outcome = coroutineScope {
        val started = System.currentTimeMillis()
        val notes = mutableListOf<String>()

        // PHASE 1 — everything that does not depend on anything else, at once.
        // These were sequential, which meant the slowest link set the floor
        // for all of them.
        val leagueAsync = async(Dispatchers.IO) { leagueRepo.load(season, leagueId) }
        val activityAsync = async(Dispatchers.IO) {
            client.get(
                client.leagueUrl(season, leagueId) +
                    "/communication/?view=kona_league_communication",
                ActivityLog.FILTER
            )
        }
        val matchupAsync = async(Dispatchers.IO) {
            val ck = "matchup_${leagueId}_$season"
            cache.get(ck, ResponseCache.TTL_HALF_DAY)
                ?: client.get(client.leagueUrl(season, leagueId, "mMatchup"))
                    .takeIf { it.ok }?.body?.also { cache.put(ck, it) }
        }
        // All 32 NFL teams regardless of league, so it shares a cache across
        // leagues and needs nothing from the league response.
        val depthAsync = async(Dispatchers.IO) {
            DepthChartLoader.load(season, (1..34).toSet(), cache)
        }

        // ---- 1. league state ------------------------------------------------
        onProgress(0.05f, "Connecting")
        val leagueOut = leagueAsync.await()
        if (leagueOut is LeagueRepository.Outcome.Failed) {
            return@coroutineScope Outcome.Failed(leagueOut.message)
        }
        val ok = leagueOut as LeagueRepository.Outcome.Ok
        ok.scheduleNote?.let { notes.add(it) }
        val week = ok.league.settings.scoringPeriodId

        // ---- 2. wire, then ownership for rostered players -------------------
        // Started only once the week is known — see PROBE_WEEK note below.
        // One pull for the wire, the market and the pool.
        val wireAllAsync = async(Dispatchers.IO) { wireRepo.loadAll(season, leagueId, week) }

        onProgress(0.25f, "League loaded")
        val wireAll = wireAllAsync.await()
        if (wireAll == null) {
            notes.add("Wire unavailable: ${wireRepo.lastPoolError ?: "unknown"}")
        }
        // Sorted by ownership velocity, which is what the tiered view keys on.
        val wire = wireAll?.available
            ?.sortedByDescending { it.percentChange }
            .orEmpty()

        // mRoster player objects carry no `ownership` key and the wire query
        // filters to free agents, so rostered ownership needs its own pull.
        val market = wireAll?.market.orEmpty()
        val league = if (market.isEmpty()) {
            notes.add("Ownership unavailable for rostered players")
            ok.league
        } else {
            ok.league.copy(teams = ok.league.teams.map { team ->
                team.copy(roster = team.roster.map { p ->
                    market[p.playerId]?.let {
                        p.copy(percentOwned = it.percentOwned,
                               percentChange = it.percentChange)
                    } ?: p
                })
            })
        }

        // ---- 3. depth charts ------------------------------------------------
        onProgress(0.55f, "Wire loaded")

        val (depth, depthNote) = depthAsync.await()
        // Record before diffing: a move needs two observations, and the first
        // has to be written even though it can show nothing.
        depthHistory.record(depth, (1..34).toSet())
        val depthMoves = depthHistory.moves(72L)
        depthNote?.let { notes.add(it) }

        // ---- 4. derived views -----------------------------------------------
        // EVERYTHING below is CPU work — tiers, at-risk pairs, power
        // rankings, predictions, the observation rewrite. It ran on the
        // caller's thread, which is the main thread, and blocked the UI for
        // around two seconds after every fetch. Default is the dispatcher
        // sized for exactly this.
        onProgress(0.75f, "Depth charts")
        withContext(Dispatchers.Default) {
            val replacement = ReplacementLevel.from(league)
            val drops = DropCandidates.build(league, ok.proTeams, replacement, week)
            val shapes = TeamShapes.build(league, ok.proTeams, replacement, week)
            val beneficiaries = Beneficiaries.build(league, wire, depth, ok.proTeams)
            val pool = wireAll?.pool.orEmpty()
            val rawRanks = wireAll?.market.orEmpty()
            .mapNotNull { (id, w) -> w.ranking?.let { id to it } }.toMap()
        val priorRanks = rankHistory.previous(season, week)
        rankHistory.record(season, week, rawRanks)
        val rankedWithDelta = rawRanks.mapValues { (id, r) ->
            priorRanks[id]?.let { was -> r.copy(delta = r.consensus - was) } ?: r
        }

        val breakouts = Breakouts.find(pool, depth, week - 1)
            val atRisk = AtRisk.find(league, wire, depth, week)
            val tiers = WireTiers.build(wire, league, replacement, ok.proTeams, depth, week)

            // ---- 5. matchups and pending claims ---------------------------------
            val pending = runCatching {
                PendingParser.fromRoster(ok.rawLeagueBody, league.myTeamId)
            }.getOrElse { emptyList() }

            // ~686KB response, so parse to the current period and drop the body.
            // 686 KB. Fixtures do not change, and live scores come from the
            // separate Scores button, so a half-day disk TTL is safe.
            val matchupBody = matchupAsync.await()
            val matchups = matchupBody?.let { body ->
                runCatching {
                    MatchupParser.parse(body, league.settings.currentMatchupPeriod)
                }.getOrElse { emptyList() }
            } ?: run { notes.add("Matchups unavailable"); emptyList() }

            // The same response, parsed for every period rather than one — the
            // season's scoring history, already fetched, previously discarded.
            val schedule = matchupBody?.let {
                runCatching { MatchupParser.parseAll(it) }.getOrElse { emptyList() }
            } ?: emptyList()
            val scores = PowerRankings.weeklyScores(schedule, league.settings.currentMatchupPeriod)
            val powerSeries = PowerRankings.series(scores)
            val currentPower = powerSeries.mapNotNull { (id, s) ->
                s.points.lastOrNull()?.let { id to it.second }
            }.toMap()
            val previousPower = powerHistory.baseline(leagueId, season, 6L)
            onProgress(0.92f, "Rankings")
        val standings = PowerRankings.standings(league, powerSeries, previousPower)
            powerHistory.record(leagueId, season, currentPower)

            // ---- 6. transactions, from ESPN's permanent log ---------------------
            val activity = activityAsync.await()
            val newTx = if (activity.ok) {
                runCatching {
                    events.append(leagueId, season, ActivityLog.parse(activity.body))
                }.getOrElse { emptyList() }
            } else {
                notes.add("Activity feed unavailable (HTTP ${activity.code})")
                emptyList()
            }

            // Remember names while these players are still on a roster — dropped
            // players vanish everywhere and history decays into integers.
            events.rememberNames(leagueId, season, buildMap {
                wire.forEach { put(it.playerId, it.name) }
                league.teams.forEach { t -> t.roster.forEach { put(it.playerId, it.name) } }
            })
            val names = events.loadNames(leagueId, season)
            val allTx = events.load(leagueId, season)

            // ---- 7. windowed diff, THEN record ----------------------------------
            val previous = snapshots.baseline(leagueId, season, DIFF_WINDOW_HOURS)
            onProgress(0.97f, "Research queue")
        val flags = ResearchQueue.generate(
                league, wire, ok.proTeams, previous, newTx, names, week
            )
            snapshots.record(leagueId, season, ResearchQueue.observationOf(league, wire))

            // Trends only for players worth watching; a series for all 400 would
            // be most of the dump.
            val trendIds = (
                beneficiaries.map { it.playerId } +
                    wire.filter { it.isMoneySignal }.map { it.playerId } +
                    (league.myTeam?.roster?.map { it.playerId } ?: emptyList())
                ).toSet()
            val trends = trendIds.mapNotNull { id ->
                val series = snapshots.series(leagueId, season, id)
                if (series.size < 2) null
                else id to Trend(id, series.map { it.first to it.second.percentOwned })
            }.toMap()

            // Archive any completed week not yet stored. This is the ONLY way a
            // past lineup survives: rosters change, and a player dropped from every
            // team loses his record everywhere. Written once, never rewritten.
            val currentPeriod = league.settings.currentMatchupPeriod
            (1 until currentPeriod).forEach { w ->
                weekArchive.archive(leagueId, season, w, currentPeriod, league, schedule)
            }
            val archivedWeeks = weekArchive.archivedWeeks(leagueId, season)
            val weekArchives = archivedWeeks.mapNotNull { w ->
                weekArchive.load(leagueId, season, w)?.let { w to it }
            }.toMap()

            onProgress(0.85f, "Computing")
        // Record what the app is claiming right now, then settle anything
            // from a completed week. Both are cheap and both must happen every
            // refresh — a prediction not captured before the games is worthless.
            predictions.record(
                leagueId, season,
                PredictionCapture.from(
                    league, week, tiers, beneficiaries, drops, depth, replacement
                )
            )
            val actualsByWeek = weeklyActualsFor(league)
            val allPredictions = predictions.all(leagueId, season)
            val verdicts = Scorecard.settle(
                allPredictions,
                actualsByWeek,
                Scorecard.actedOnIds(league, allTx),
                league.settings.currentMatchupPeriod
            )
            val scorecard = Scorecard.summarise(verdicts)

            if (previous == null) {
                notes.add(
                    "Building history — ownership and projection changes need a " +
                        "second run at least 20 minutes later to compare against. " +
                        "Transactions are unaffected; they come from ESPN's log."
                )
            }

            Outcome.Ok(
                Brief(
                    league = league,
                    proTeams = ok.proTeams,
                    depth = depth,
                    wire = wire,
                    flags = flags,
                    replacement = replacement,
                    dropCandidates = drops,
                    shapes = shapes,
                    beneficiaries = beneficiaries,
                    trends = trends,
                    tiers = tiers,
                    pool = pool,
                    breakouts = breakouts,
                    atRisk = atRisk,
                rankings = rankedWithDelta,
                    depthMoves = depthMoves,
                    depthObservations = depthHistory.observationCount(),
                    matchups = matchups,
                    schedule = schedule,
                    standings = standings,
                    powerSeries = powerSeries,
                    powerChartable = PowerRankings.chartable(powerSeries),
                    archivedWeeks = archivedWeeks,
                    weekArchives = weekArchives,
                    predictions = allPredictions,
                    verdicts = verdicts,
                    scorecard = scorecard,
                    pending = pending,
                    transactions = allTx,
                    newTransactions = newTx,
                    playerNames = names,
                    previousSnapshotAgeHours = previous?.ageHours,
                    baselineAgeMinutes = previous?.ageMinutes,
                    runCount = snapshots.runCount(leagueId, season),
                    notes = notes,
                    millis = System.currentTimeMillis() - started
                )
            )
        }
    }

    /**
     * Weekly actuals for every player in the league, keyed week -> id -> pts.
     * Rostered players only — a player dropped before his week was scored
     * cannot be settled, which is a known and accepted gap.
     */
    private fun weeklyActualsFor(league: League): Map<Int, Map<Int, Double>> {
        val out = mutableMapOf<Int, MutableMap<Int, Double>>()
        league.teams.forEach { t ->
            t.roster.forEach { p ->
                p.weeklyActuals.forEach { (week, pts) ->
                    out.getOrPut(week) { mutableMapOf() }[p.playerId] = pts
                }
            }
        }
        return out
    }

    fun resetSnapshot(season: Int, leagueId: Long) = snapshots.clear(leagueId, season)

    /** Forces slow-changing data to be refetched on the next load. */
    fun clearResponseCache() = cache.clear()

    private companion object {
        const val DIFF_WINDOW_HOURS = 24L
        // Wire projections need the real scoring period, so the wire fetch
        // waits for the league response rather than guessing. Everything else
        // in phase 1 still runs concurrently.
    }
}
