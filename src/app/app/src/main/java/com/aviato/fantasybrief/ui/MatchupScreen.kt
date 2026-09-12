package com.aviato.fantasybrief.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.EspnClient
import com.aviato.fantasybrief.data.SecretStore
import com.aviato.fantasybrief.data.WeekProjections
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.FantasyTeam
import com.aviato.fantasybrief.data.GameState
import com.aviato.fantasybrief.data.LiveScoreboard
import com.aviato.fantasybrief.data.ProTeamIndex
import com.aviato.fantasybrief.data.RosterPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

/** One paired row: same slot, both sides, plus whatever is known live. */
private data class PairRow(
    val slotLabel: String,
    val mine: RosterPlayer?,
    val theirs: RosterPlayer?,
    val mineLive: Double?,
    val theirsLive: Double?,
    val isBench: Boolean,
    /** Our own live projection: scored so far plus a pro-rated remainder. */
    val mineProj: Double? = null,
    val theirsProj: Double? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchupScreen(
    brief: Brief?,
    loading: Boolean,
    live: LiveScoreboard?,
    scoresLoading: Boolean,
    onRefreshScores: () -> Unit,
    onRefreshAll: () -> Unit = {},
    selectedWeek: Int? = null,
    onSelectWeek: (Int) -> Unit = {},
    bottomInset: Dp = 0.dp,
    onPlayer: (PlayerFocus) -> Unit = {},
    onMovePlayer: ((RosterPlayer) -> Unit)? = null
) {
    if (loading || brief == null) {
        Column(
            Modifier.fillMaxSize().background(Ink.ground),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) { CircularProgressIndicator(color = Ink.accent) }
        return
    }

    // One request, writes nothing. Entering the tab should just be current.
    LaunchedEffect(brief.league.id) { onRefreshScores() }

    val league = brief.league
    val settings = league.settings
    val pro = brief.proTeams
    val currentWeek = settings.scoringPeriodId
    val week = selectedWeek ?: currentWeek
    val isCurrentWeek = week == currentWeek
    val isPast = week < currentWeek
    // A completed week is read from the archive when we have one — the
    // lineup that played, not the roster as it stands now.
    val archive = if (isPast) brief.weekArchives[week] else null

    // WeekProjections covers the whole league in one request, so both sides
    // of the matchup come from the same fetch. ~1.2 MB, cached per week for
    // the session.
    var futureProj by remember { mutableStateOf<Map<Int, Double>>(emptyMap()) }
    var loadingWeek by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(week, league.id) {
        if (isCurrentWeek || isPast) {
            futureProj = emptyMap()
            return@LaunchedEffect
        }
        loadingWeek = true
        futureProj = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            WeekProjections(EspnClient(SecretStore(context)))
                .load(league.season, league.id, week)
        }
        loadingWeek = false
    }

    // The projection to show for a player in the selected week. Bye first —
    // he scores nothing regardless of what any projection says.
    fun projFor(p: RosterPlayer?): Double? {
        if (p == null) return null
        if (pro.isOnBye(p.proTeamId, week)) return 0.0
        if (isCurrentWeek || isPast) return p.projection
        // Null rather than the current week's number: a stale value that looks
        // current is worse than a dash.
        return if (futureProj.isEmpty()) p.projection else futureProj[p.playerId]
    }
    val myId = league.myTeamId

    val myMatchup = brief.schedule
        .filter { it.matchupPeriodId == week }
        .firstOrNull { it.homeTeamId == myId || it.awayTeamId == myId }
        ?: brief.matchups.firstOrNull { it.homeTeamId == myId || it.awayTeamId == myId }

    val allGames = remember(brief, week) {
        b0@ run {
            val games = brief.schedule.filter { it.matchupPeriodId == week }
                .ifEmpty { brief.matchups }
            val mine = games.firstOrNull { it.homeTeamId == myId || it.awayTeamId == myId }
            (listOfNotNull(mine) + games.filterNot { it === mine })
        }
    }
    val headerPager = rememberPagerState(pageCount = { maxOf(1, allGames.size) })

    // The tiles follow the pager: one game, one view. Falls back to mine when
    // the schedule has nothing for this week.
    val matchup = allGames.getOrNull(headerPager.currentPage) ?: myMatchup

    // Mine on the left whenever I am in this game, so the layout does not
    // flip as you page past it.
    val homeTeam = league.teams.firstOrNull { it.id == matchup?.homeTeamId }
    val awayTeam = league.teams.firstOrNull { it.id == matchup?.awayTeamId }
    val me = when (myId) {
        awayTeam?.id -> awayTeam
        else -> homeTeam
    }
    val them = if (me?.id == homeTeam?.id) awayTeam else homeTeam
    if (matchup == null || me == null || them == null) {
        Column(Modifier.fillMaxSize().background(Ink.ground).padding(20.dp)) {
            Text("No game found for week $week", style = inkBody(14.0, Ink.mid))
        }
        return
    }

    /**
     * Live projection for one player: what he has scored, plus his pregame
     * projection scaled by how much of his game is left. ESPN publishes this
     * per TEAM but not per player, so this is our own estimate and will
     * differ from theirs by a few points.
     */
    fun liveProjFor(team: FantasyTeam, p: RosterPlayer?): Double? {
        if (p == null) return null
        if (!isCurrentWeek) return projFor(p)
        if (pro.isOnBye(p.proTeamId, week)) return 0.0
        val game = live?.games?.get(p.proTeamId)
        val pre = p.projection ?: return null
        if (game == null || !game.started) return pre
        val scored = live.points(team.id, p.playerId)
        return (scored ?: 0.0) + pre * game.fractionRemaining
    }

    fun livePts(team: FantasyTeam, p: RosterPlayer?): Double? {
        if (p == null) return null
        if (pro.isOnBye(p.proTeamId, week)) return null
        // Archive first: it holds the points as they were, including for
        // players since dropped. Falls back to the player's own record.
        if (isPast) {
            archive?.rosters?.get(team.id)?.players
                ?.firstOrNull { it.playerId == p.playerId }?.let { return it.actual }
            return p.weeklyActuals[week]
        }
        if (!isCurrentWeek) return null   // future week: projections only
        // hasStarted is LEAGUE-wide — true the moment the Thursday game
        // kicks off — so it handed 0.0 to every Sunday player as though they
        // had played and scored nothing. Gate on HIS game instead: a dash
        // until his kickoff, then 0.0 climbing from there.
        if (!pro.hasKickedOff(p.proTeamId, week)) return null
        return live?.points(team.id, p.playerId)
    }

    // Starters pair by slot; bench pairs by list position and means nothing.
    // Live slots win: a manager who moved someone this morning should show
    // correctly without waiting for a full refresh.
    fun slotOf(team: FantasyTeam, p: RosterPlayer): Int {
        archive?.rosters?.get(team.id)?.players
            ?.firstOrNull { it.playerId == p.playerId }?.let { return it.lineupSlotId }
        return live?.slot(team.id, p.playerId) ?: p.lineupSlotId
    }

    val starterRows = settings.starterSlots
        .sortedBy { Enums.slotSortKey(it.slotId) }
        .flatMap { rule ->
        val mineAt = me.roster.filter { slotOf(me, it) == rule.slotId }
            .sortedByDescending { projFor(it) ?: 0.0 }
        val theirsAt = them.roster.filter { slotOf(them, it) == rule.slotId }
            .sortedByDescending { projFor(it) ?: 0.0 }
        (0 until rule.count).map { i ->
            val a = mineAt.getOrNull(i)
            val b = theirsAt.getOrNull(i)
            PairRow(
                slotLabel = Enums.slot(rule.slotId), mine = a, theirs = b,
                mineLive = livePts(me, a), theirsLive = livePts(them, b),
                isBench = false,
                mineProj = liveProjFor(me, a),
                theirsProj = liveProjFor(them, b)
            )
        }
    }
    val myBench = me.roster.filter { !Enums.isStarterSlot(slotOf(me, it)) }
    val theirBench = them.roster.filter { !Enums.isStarterSlot(slotOf(them, it)) }
    val benchRows = (0 until maxOf(myBench.size, theirBench.size)).map { i ->
        val a = myBench.getOrNull(i)
        val b = theirBench.getOrNull(i)
        PairRow(
            slotLabel = "BN", mine = a, theirs = b,
            mineLive = livePts(me, a), theirsLive = livePts(them, b),
            isBench = true,
            mineProj = liveProjFor(me, a),
            theirsProj = liveProjFor(them, b)
        )
    }

    // ONE diff pass. Markers and the EDGE/GAP sentences both read from it, so
    // they cannot disagree.
    val diffs = starterRows.mapIndexedNotNull { i, r ->
        val m = r.mineLive; val t = r.theirsLive
        if (m != null && t != null) i to (m - t) else null
    }
    val edgeIdx = if (diffs.size >= 2) diffs.maxByOrNull { it.second }?.first else null
    val gapIdxRaw = if (diffs.size >= 2) diffs.minByOrNull { it.second }?.first else null
    val gapIdx = if (gapIdxRaw == edgeIdx) null else gapIdxRaw

    // The week begins at the FIRST kickoff, and the schedule knows that
    // without a fetch. Keying on the live scoreboard meant a failed request
    // reverted a mid-game header to dashes.
    val started = live?.hasStarted == true ||
        (isCurrentWeek && league.teams.any { t ->
            t.roster.any { pro.hasKickedOff(it.proTeamId, week) }
        })
    // ESPN's live projection when the week is under way; our own sum
    // otherwise, and for weeks they do not publish.
    val myProjected = live?.projectedLive(me.id)
        ?.takeIf { isCurrentWeek }
        ?: me.roster.filter { it.isStarter }.sumOf { projFor(it) ?: 0.0 }
    val theirProjected = live?.projectedLive(them.id)
        ?.takeIf { isCurrentWeek }
        ?: them.roster.filter { it.isStarter }.sumOf { projFor(it) ?: 0.0 }
    // NULL until something has actually been scored. Showing the projection
    // as the big number implied points that do not exist, and duplicated the
    // line beneath it.
    // Sum only the players whose games have actually kicked off, so the
    // header agrees with the tiles rather than counting unplayed slots.
    fun liveTotal(t: FantasyTeam): Double? {
        if (!started) return null
        val playing = t.roster.filter {
            it.isStarter && pro.hasKickedOff(it.proTeamId, week) &&
                !pro.isOnBye(it.proTeamId, week)
        }
        // 0.00 is a real score once anything has kicked off; only a week
        // that has not begun at all gets a dash.
        return playing.sumOf { live?.points(t.id, it.playerId) ?: 0.0 }
    }
    val myTotal: Double? = liveTotal(me)
    val theirTotal: Double? = liveTotal(them)

    // The margin and the bar have to compare like with like: real points once
    // they exist, projections before that.
    val myForMargin = myTotal ?: myProjected
    val theirForMargin = theirTotal ?: theirProjected

    val myLeft = me.roster.count {
        it.isStarter && !pro.hasKickedOff(it.proTeamId, week) &&
            !pro.isOnBye(it.proTeamId, week)
    }
    val theirLeft = them.roster.count {
        it.isStarter && !pro.hasKickedOff(it.proTeamId, week) &&
            !pro.isOnBye(it.proTeamId, week)
    }

    // All five games, mine first. Header only: the slot rows below stay on
    // my own matchup, because a second full-screen horizontal gesture would
    // fight the league swipe.

    Column(Modifier.fillMaxSize().background(Ink.ground)) {
        HorizontalPager(state = headerPager, modifier = Modifier.fillMaxWidth()) { page ->
            // Every page renders the SELECTED game's numbers, which the
            // outer scope already computed — one source, no drift.
            run {
                MatchupHeader(
                    week = week, me = me, them = them,
                    myTotal = myTotal, theirTotal = theirTotal,
                    myForMargin = myForMargin, theirForMargin = theirForMargin,
                    myProjected = myProjected, theirProjected = theirProjected,
                    myLeft = myLeft, theirLeft = theirLeft,
                    started = started, scoresLoading = scoresLoading,
                    winProb = live?.winProbability(me.id),
                    isMine = matchup?.let {
                        it.homeTeamId == myId || it.awayTeamId == myId
                    } ?: true,
                    page = page, pageCount = allGames.size,
                    onRefreshScores = onRefreshScores
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = scoresLoading,
            onRefresh = onRefreshAll,
            modifier = Modifier.fillMaxSize()
        ) {
        Column(Modifier.fillMaxSize()) {
        WeekStrip(
            weeks = brief.schedule.map { it.matchupPeriodId }
                .filter { it > 0 }.distinct().sorted(),
            selected = week, current = currentWeek, onSelect = onSelectWeek
        )

        if (isPast) {
            Text(
                if (archive != null)
                    "Completed week, from the lineup archive \u2014 the players " +
                        "and slots as they stood when it was played."
                else "Completed week. No archive for it, so this shows current " +
                    "rosters with that week's points; anyone since dropped is " +
                    "missing.",
                style = inkBody(10.5, Ink.mid),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        } else if (!isCurrentWeek) {
            Text(
                if (loadingWeek) "Week $week \u2014 loading projections..."
                else if (futureProj.isNotEmpty())
                    "Week $week projections for both sides. Lineups will still " +
                        "change before it is played."
                else "Week $week. Projections unavailable.",
                style = inkBody(10.5, Ink.mid),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = bottomInset + 24.dp)
        ) {
            item { InkSection("STARTERS", topPadding = 6.dp) }
            itemsIndexedRows(starterRows) { i, row ->
                PairedRow(
                    row, brief, week, me, them, live, ::projFor,
                    marker = when (i) {
                        edgeIdx -> "\u25B2" to Ink.positive
                        gapIdx -> "\u25BC" to Ink.negative
                        else -> null
                    },
                    onPlayer = onPlayer, onMove = onMovePlayer
                )
            }

            if (benchRows.isNotEmpty()) {
                item { InkSection("BENCH", trailing = "NOT SCORING") }
                itemsIndexedRows(benchRows) { _, row ->
                    PairedRow(row, brief, week, me, them, live, ::projFor,
                        marker = null,
                        onPlayer = onPlayer, onMove = onMovePlayer)
                }
            }

            item {
                WhatDecidesIt(
                    me, them, brief, week, starterRows,
                    edgeIdx, gapIdx, myLeft, theirLeft
                )
            }
        }
        }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedRows(
    rows: List<PairRow>,
    content: @Composable (Int, PairRow) -> Unit
) = items(rows.size) { i -> content(i, rows[i]) }

private fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int, content: @Composable (Int) -> Unit
) = items(count = count) { content(it) }

// ---- header --------------------------------------------------------------

@Composable
private fun WeekStrip(
    weeks: List<Int>,
    selected: Int,
    current: Int,
    onSelect: (Int) -> Unit
) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = (weeks.indexOf(selected) - 2).coerceAtLeast(0)
    )
    androidx.compose.foundation.lazy.LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(weeks.size) { i ->
            val w = weeks[i]
            val isSel = w == selected
            Box(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSel) Ink.accent.copy(alpha = 0.20f) else Color.Transparent
                    )
                    .border(
                        1.dp,
                        if (isSel) Ink.accent
                        else if (w == current) Ink.positive.copy(alpha = 0.5f)
                        else Ink.border,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(w) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "W$w",
                    style = inkLabel(10.5,
                        when {
                            isSel -> Ink.paper
                            w == current -> Ink.positive
                            w < current -> Ink.mid
                            else -> Ink.mid.copy(alpha = 0.6f)
                        })
                )
            }
        }
    }
}

@Composable
private fun MatchupHeader(
    week: Int, me: FantasyTeam, them: FantasyTeam,
    myTotal: Double?, theirTotal: Double?,
    myForMargin: Double, theirForMargin: Double,
    myProjected: Double, theirProjected: Double,
    myLeft: Int, theirLeft: Int,
    started: Boolean, scoresLoading: Boolean,
    isMine: Boolean = true, page: Int = 0, pageCount: Int = 1,
    /** ESPN's own win probability for the left-hand team, 0..1. */
    winProb: Double? = null,
    onRefreshScores: () -> Unit
) {
    val margin = myForMargin - theirForMargin
    val marginColor = if (margin >= 0) Ink.positive else Ink.negative

    Column(
        Modifier.fillMaxWidth().background(Ink.ground)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isMine) "WEEK $week" else "WEEK $week \u00B7 OTHER GAME",
                style = inkLabel(11.0, if (isMine) Ink.mid else Ink.accent)
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(4.dp))
                    .border(1.dp, marginColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(signed1(margin), style = inkNum(11.5, marginColor, FontWeight.SemiBold))
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(5.dp))
                    .border(1.dp, Ink.accent, RoundedCornerShape(5.dp))
                    .clickable(enabled = !scoresLoading) { onRefreshScores() }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(if (scoresLoading) "\u2026" else "SCORES",
                    style = inkLabel(10.5, Ink.accent))
            }
        }

        Spacer(Modifier.height(26.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(top = 14.dp)) {
                TeamLogo(me.logoUrl, me.name, 68.dp, Ink.positive)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                SplitTotal(myTotal, 36.0, 19.0, Ink.paper, TextAlign.Start)
                Text(
                    "${two(myProjected)}",
                    style = inkNum(
                        13.6, Ink.paper,
                        if (myProjected >= theirProjected) FontWeight.Bold
                        else FontWeight.Normal
                    )
                )
                Text("${myLeft} left", style = inkNum(11.0, Ink.mid))
                Text(me.name.uppercase(), style = inkLabel(13.0, Ink.paper),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Text("VS", style = inkLabel(11.0, Ink.mid),
                modifier = Modifier.padding(horizontal = 8.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                SplitTotal(theirTotal, 36.0, 19.0, Ink.paper, TextAlign.End,
                    Modifier.fillMaxWidth())
                Text(
                    "${two(theirProjected)}",
                    style = inkNum(
                        13.6, Ink.paper,
                        if (theirProjected > myProjected) FontWeight.Bold
                        else FontWeight.Normal
                    )
                )
                Text("${theirLeft} left", style = inkNum(11.0, Ink.mid))
                Text(them.name.uppercase(), style = inkLabel(13.0, Ink.mid),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.padding(top = 14.dp)) {
                TeamLogo(them.logoUrl, them.name, 68.dp, Ink.mid)
            }
        }

        if (pageCount > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pageCount) { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp)
                            .size(if (i == page) 6.dp else 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (i == page) Ink.accent else Ink.mid.copy(alpha = 0.4f)
                            )
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // Win probability when ESPN publishes it, the score split
        // otherwise. A 0-vs-24 bar early in the week is accurate and
        // tells you nothing.
        val myShare = winProb
            ?: (myForMargin / (myForMargin + theirForMargin).coerceAtLeast(0.01))
        Row(
            Modifier.fillMaxWidth().height(4.dp)
                .clip(RoundedCornerShape(3.dp)).background(Ink.barTrack)
        ) {
            Box(
                Modifier.weight(myShare.toFloat().coerceIn(0.02f, 0.98f))
                    .fillMaxSize().background(Ink.positive)
            )
            Box(Modifier.weight((1.0 - myShare).toFloat().coerceIn(0.02f, 0.98f)))
        }

        // The bar means two different things depending on whether ESPN
        // publishes a probability, so it says which one.
        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                winProb?.let { "${(it * 100).toInt()}%" } ?: "",
                style = inkNum(10.0, Ink.positive)
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (winProb != null) "CHANCE TO WIN" else "PROJECTED SPLIT",
                style = inkLabel(8.5, Ink.mid)
            )
            Spacer(Modifier.weight(1f))
            Text(
                winProb?.let { "${100 - (it * 100).toInt()}%" } ?: "",
                style = inkNum(10.0, Ink.mid)
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
}

// ---- sections ------------------------------------------------------------

@Composable
private fun InkSection(
    title: String,
    trailing: String? = null,
    topPadding: Dp = 20.dp
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = inkLabel(11.5, Ink.accent))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Ink.border))
        trailing?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, style = inkLabel(9.5, Ink.mid))
        }
    }
}

// ---- paired row ----------------------------------------------------------

@Composable
private fun PairedRow(
    row: PairRow,
    brief: Brief,
    week: Int,
    me: FantasyTeam,
    them: FantasyTeam,
    live: LiveScoreboard?,
    weekProj: (RosterPlayer?) -> Double?,
    marker: Pair<String, Color>?,
    onPlayer: (PlayerFocus) -> Unit,
    onMove: ((RosterPlayer) -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
            .then(if (row.isBench) Modifier.alpha(0.70f) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerTile(row.mine, row.mineLive, row.mineProj ?: weekProj(row.mine),
            brief, week,
            me.id, false, row.isBench,
            isMine = true,
            game = row.mine?.let { live?.game(it.proTeamId) },
            onPlayer = onPlayer, onMove = onMove)
        Column(
            Modifier.width(30.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(row.slotLabel, style = inkLabel(10.0, Ink.mid))
            // Bench points cannot move the margin, so bench rows never carry
            // a marker.
            if (!row.isBench) marker?.let { (glyph, color) ->
                Text(glyph, style = inkNum(9.0, color), modifier = Modifier.padding(top = 2.dp))
            }
        }
        PlayerTile(row.theirs, row.theirsLive, row.theirsProj ?: weekProj(row.theirs),
            brief, week,
            them.id, true, row.isBench,
            isMine = false,
            game = row.theirs?.let { live?.game(it.proTeamId) },
            onPlayer = onPlayer, onMove = null)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.PlayerTile(
    p: RosterPlayer?,
    livePts: Double?,
    weekProjection: Double?,
    brief: Brief,
    week: Int,
    teamId: Int,
    mirrored: Boolean,
    isBench: Boolean,
    isMine: Boolean,
    game: GameState?,
    onPlayer: (PlayerFocus) -> Unit,
    onMove: ((RosterPlayer) -> Unit)?
) {
    val pro = brief.proTeams
    // Fill marks LOCKED, not scored: a back who has played a quarter without
    // scoring is not the same as one who has not kicked off.
    val kicked = p != null && (game?.started ?: pro.hasKickedOff(p.proTeamId, week))
    val onByeTile = p != null && pro.isOnBye(p.proTeamId, week)
    // A defence has no face and its own logo IS the identity, so the
    // headshot slot carries the team mark and the inline logo is dropped
    // to avoid showing it twice.
    val isDst = p != null && p.positionId == 16
    val align = if (mirrored) Alignment.End else Alignment.Start
    val textAlign = if (mirrored) TextAlign.End else TextAlign.Start

    // PROJECTION, never live points. Replacement level is a projected
    // number, so feeding actual points in made every tile read BELOW before
    // kickoff and LEGENDARY after a good quarter — the tier describes the
    // player, not how his afternoon is going.
    val isCurrentWeek = week == brief.league.settings.scoringPeriodId
    val tierName = p?.takeIf { isCurrentWeek }?.let {
        brief.tierNameOf(it.position, weekProjection, it.playerId)
    }
    val legendary = tierName == "LEGENDARY"
    val txt = if (legendary) Fb.LegendaryText else Ink.paper
    val txtDim = if (legendary) Fb.LegendaryText else Ink.mid
    val spine = p?.let {
        // Replacement level and rankings are always CURRENT. Tiering a
        // past week against them would measure week 3 by week 9's median,
        // so those tiles stay neutral instead of asserting a colour.
        if (!isCurrentWeek) Ink.border
        else inkTierSpine(brief.tierNameOf(it.position, weekProjection, it.playerId))
    } ?: Color.Transparent

    Row(
        Modifier.weight(1f)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(9.dp))
            .background(
                tierFill(
                    tierName,
                    when {
                        onByeTile -> Ink.negative.copy(alpha = 0.10f)
                        // Locked keeps the accent fill; everything else gets a
                        // light base so it reads as a card rather than a hole.
                        kicked -> Ink.tileFill
                        else -> Ink.paper.copy(alpha = 0.045f)
                    }
                )
            )
            .border(
                tierBorderWidth(tierName).dp,
                tierBorder(
                    tierName,
                    if (onByeTile) Ink.negative else Ink.border
                ),
                RoundedCornerShape(9.dp)
            )
            .then(
                if (p == null) Modifier
                else Modifier.combinedClickable(
                    onClick = { onPlayer(p.focus(teamId)) },
                    // Never once the tile is lit — a locked player cannot move,
                    // so the gesture does nothing rather than opening a dead
                    // sheet.
                    onLongClick = if (isMine && !kicked && onMove != null)
                        { { onMove(p) } } else null
                )
            )
    ) {
        if (!mirrored) Box(Modifier.width(3.dp).fillMaxHeight().background(spine))

        Column(
            Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, top = 9.dp, bottom = 7.dp),
            horizontalAlignment = align
        ) {
            if (p == null) {
                Text("\u2014 empty", style = inkBody(12.5, Ink.mid),
                    textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(30.dp))
                return@Column
            }

            // Face on the outer edge, everything else beside it. The opponent
            // mirrors, so their points land in the outer top corner.
            Row(Modifier.fillMaxWidth()) {
                if (!mirrored) {
                    RankedHeadshot(
                        p.playerId, p.name,
                        brief.rankings[p.playerId]?.badge(),
                        brief.rankings[p.playerId]?.delta,
                        40.dp, Ink.accent, isDst, pro.abbrev(p.proTeamId)
                    )
                    Spacer(Modifier.width(9.dp))
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = align
                ) {
                    // Logo above the numbers, on the inner edge.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            if (mirrored) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mirrored, so read from the OUTER edge inward: points
                        // first on both sides, then the logo, then the face.
                        if (mirrored) {
                            // Theirs: logo stays inner, score pushed to the
                            // far right edge of the tile.
                            // Theirs: score inner, logo pushed to the far
                            // right edge of the tile.
                            SplitTotal(
                                livePts,
                                19.0, 12.0,
                                if (livePts != null) txt else txtDim,
                                TextAlign.Start
                            )
                            Spacer(Modifier.weight(1f))
                            if (!isDst) ProTeamLogo(
                                pro.abbrev(p.proTeamId), 18.dp, Ink.accent)
                        } else {
                            // Mine: logo pushed to the far left edge.
                            if (!isDst) {
                                ProTeamLogo(pro.abbrev(p.proTeamId), 18.dp, Ink.accent)
                            }
                            Spacer(Modifier.weight(1f))
                            SplitTotal(
                                livePts,
                                19.0, 12.0,
                                if (livePts != null) txt else txtDim,
                                TextAlign.End
                            )
                        }
                    }

                    // The one coloured line: what happened against what was
                    // expected.
                    Text(
                        when {
                            onByeTile -> "on bye"
                            weekProjection != null -> "${two(weekProjection)} proj"
                            else -> ""
                        },
                        style = inkNum(9.0, txtDim, FontWeight.Normal),
                        textAlign = textAlign, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (mirrored) {
                    Spacer(Modifier.width(9.dp))
                    RankedHeadshot(
                        p.playerId, p.name,
                        brief.rankings[p.playerId]?.badge(),
                        brief.rankings[p.playerId]?.delta,
                        40.dp, Ink.accent, isDst, pro.abbrev(p.proTeamId)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (mirrored) Arrangement.End else Arrangement.Start
            ) {
                if (mirrored) {
                    if (!isBench && !p.healthy) {
                        InjuryPill(p.injuryTag); Spacer(Modifier.width(5.dp))
                    }
                    Text(p.position, style = inkLabel(9.0, txtDim))
                    Spacer(Modifier.width(5.dp))
                    Text(p.name, style = inkBody(13.0, txtDim), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                } else {
                    Text(p.name, style = inkBody(13.0, txt), maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, false))
                    Spacer(Modifier.width(5.dp))
                    Text(p.position, style = inkLabel(9.0, txtDim))
                    if (!isBench && !p.healthy) {
                        Spacer(Modifier.width(5.dp)); InjuryPill(p.injuryTag)
                    }
                }
            }

            val abbrev = pro.abbrev(p.proTeamId)
            val kick = pro.kickoffLabel(p.proTeamId, week)
            val line = when {
                onByeTile -> "BYE"
                game != null -> game.line(abbrev) + " \u00B7 " + game.statusText.uppercase()
                else -> {
                    val fixture = pro.opponent(p.proTeamId, week)
                    when {
                        fixture == null && kick == null -> ""
                        fixture == null -> kick!!
                        kick == null -> "$abbrev $fixture"
                        else -> "$abbrev $fixture \u00B7 ${kick.uppercase()}"
                    }
                }
            }
            if (line.isNotBlank()) {
                Text(line, style = inkNum(9.0, txtDim, FontWeight.Normal),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth().alpha(0.85f))
            }
        }

        if (mirrored) Box(Modifier.width(3.dp).fillMaxHeight().background(spine))
    }
}

@Composable
private fun TeamMark(abbrev: String) {
    // Real logo now; the framed abbreviation was always a placeholder.
    ProTeamLogo(abbrev, 26.dp, Ink.accent)
}

@Composable
private fun TeamMarkFramed(abbrev: String) {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))
            .border(1.dp, Ink.border, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) {
        // Real logos drop into this exact box later. Keep the frame; do not go
        // circular and do not introduce headshots.
        Text(abbrev, style = inkLabel(8.5, Ink.accent))
    }
}

@Composable
private fun InjuryPill(tag: String) {
    val color = if (tag.startsWith("O") || tag == "IR") Ink.negative else Ink.accent
    Box(
        Modifier.clip(RoundedCornerShape(3.dp))
            .border(1.dp, color, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) { Text(tag, style = inkLabel(8.5, color)) }
}

// ---- what decides it -----------------------------------------------------

@Composable
private fun WhatDecidesIt(
    me: FantasyTeam,
    them: FantasyTeam,
    brief: Brief,
    week: Int,
    rows: List<PairRow>,
    edgeIdx: Int?,
    gapIdx: Int?,
    myLeft: Int,
    theirLeft: Int
) {
    val pro = brief.proTeams

    val lines = buildList<Triple<String, String, Color>> {
        add(Triple("LEFT",
            "You have $myLeft starters to play, they have $theirLeft.", Ink.paper))

        // Derived from the SAME diff array as the markers, so they cannot
        // contradict each other.
        edgeIdx?.let { i ->
            val r = rows[i]
            val d = (r.mineLive ?: 0.0) - (r.theirsLive ?: 0.0)
            add(Triple("EDGE \u25B2",
                "${r.mine?.position} ${r.mine?.name} is ${signed1(d)} on their " +
                    "${r.theirs?.position}.", Ink.positive))
        }
        gapIdx?.let { i ->
            val r = rows[i]
            val d = (r.mineLive ?: 0.0) - (r.theirsLive ?: 0.0)
            add(Triple("GAP \u25BC",
                "${r.mine?.position} ${r.mine?.name} is ${signed1(d)} on their " +
                    "${r.theirs?.position}.", Ink.negative))
        }

        me.roster.filter { it.isStarter && !it.healthy }.forEach {
            add(Triple("OUT", "${it.name} is ${it.injuryStatus} in your lineup.",
                Ink.negative))
        }
        them.roster.filter { it.isStarter && !it.healthy }.forEach {
            add(Triple("THEM", "${it.name} is ${it.injuryStatus} for ${them.name}.",
                Ink.positive))
        }
        (me.roster + them.roster)
            .filter { it.isStarter && pro.isOnBye(it.proTeamId, week) }
            .forEach {
                add(Triple("BYE", "${it.name} is on bye in a starting slot.", Ink.negative))
            }
    }

    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        InkSection("WHAT DECIDES IT")
        lines.take(6).forEach { (tag, body, color) ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Text(tag, style = inkLabel(9.5, Ink.mid), modifier = Modifier.width(54.dp))
                Text(body, style = inkBody(12.0, color), lineHeight = 17.sp)
            }
        }
    }
}
