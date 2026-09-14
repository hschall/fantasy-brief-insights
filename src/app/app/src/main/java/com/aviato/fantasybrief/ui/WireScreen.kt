package com.aviato.fantasybrief.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.BoardEntry
import com.aviato.fantasybrief.data.BoardSort
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.DepthMove
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.Tier
import com.aviato.fantasybrief.data.WireBoard
import com.aviato.fantasybrief.data.WirePlayer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.itemsIndexed

private enum class WireTab { BOARD, DEPTH }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WireScreen(
    brief: Brief?,
    loading: Boolean,
    bottomInset: Dp = 0.dp,
    onPlayer: (PlayerFocus) -> Unit = {},
    onAcquire: ((WirePlayer) -> Unit)? = null,
    inFlightCount: Int = 0,
    onOpenClaims: (() -> Unit)? = null,
    state: LeagueScreenState = remember { LeagueScreenState() },
    global: GlobalScreenState = remember { GlobalScreenState() },
    onRefresh: () -> Unit = {},
    /** Same collection the Insights candidates star writes to. */
    starred: Set<Int> = emptySet(),
    onStar: ((Int) -> Unit)? = null,
) {
    // Hoisted: these were disposed on every swipe away.
    val tab = if (global.wireTab == 0) WireTab.BOARD else WireTab.DEPTH
    val position = global.wirePosition
    val sort = global.wireSort
    val startersOnly = global.wireStartersOnly

    if (loading || brief == null) {
        Column(
            Modifier.fillMaxSize().background(Ink.ground),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) { CircularProgressIndicator(color = Ink.accent) }
        return
    }

    val b = brief
    val settings = b.league.settings
    val entries = remember(b) {
        WireBoard.build(
            b.league, b.pool, b.tiers, b.depth, b.transactions, b.replacement
        )
    }

    Column(Modifier.fillMaxSize().background(Ink.ground)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp,
                top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("WIRE", style = inkLabel(15.0, Ink.paper))
            Spacer(Modifier.width(10.dp))
            Text(
                "week ${settings.currentMatchupPeriod} \u00B7 ${settings.name}",
                style = inkBody(11.5, Ink.mid), modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            onOpenClaims?.let {
                Box(
                    Modifier.clip(RoundedCornerShape(5.dp))
                        .border(1.dp, Ink.accent, RoundedCornerShape(5.dp))
                        .clickable { it() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        "WAIVER ${b.league.myTeam?.waiverRank ?: "-"}" +
                            if (inFlightCount > 0) " \u00B7 $inFlightCount" else "",
                        style = inkLabel(9.5, Ink.accent)
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth()) {
            WireTab.entries.forEach { t ->
                Column(
                    Modifier.weight(1f).clickable {
                        global.wireTab = if (t == WireTab.BOARD) 0 else 1
                    },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (t == WireTab.BOARD) "BOARD" else "DEPTH",
                        style = inkLabel(11.5, if (tab == t) Ink.accent else Ink.mid),
                        modifier = Modifier.padding(vertical = 11.dp)
                    )
                    Box(
                        Modifier.fillMaxWidth().height(2.dp)
                            .background(if (tab == t) Ink.accent else Color.Transparent)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))

        when (tab) {
            WireTab.BOARD -> BoardTab(
                b = b,
                entries = entries,
                position = position,
                sort = sort,
                startersOnly = startersOnly,
                scrollState = state.wireScroll,
                bottomInset = bottomInset,
                onPosition = { global.wirePosition = it },
                onSort = { global.wireSort = it },
                onStartersOnly = { global.wireStartersOnly = it },
                onPlayer = onPlayer,
                onAcquire = onAcquire,
                starred = starred,
                onStar = onStar,
                starredOnly = global.wireStarredOnly,
                onStarredOnly = { global.wireStarredOnly = it }
            )
            WireTab.DEPTH -> DepthTab(b, bottomInset, onPlayer)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun BoardTab(
    b: Brief,
    entries: List<BoardEntry>,
    position: String?,
    sort: BoardSort,
    startersOnly: Boolean,
    bottomInset: Dp,
    onPosition: (String?) -> Unit,
    onSort: (BoardSort) -> Unit,
    onStartersOnly: (Boolean) -> Unit,
    scrollState: androidx.compose.foundation.lazy.LazyListState,
    onPlayer: (PlayerFocus) -> Unit,
    onAcquire: ((WirePlayer) -> Unit)?,
    starred: Set<Int> = emptySet(),
    onStar: ((Int) -> Unit)? = null,
    starredOnly: Boolean = false,
    onStarredOnly: ((Boolean) -> Unit)? = null
) {
    val shown = remember(entries, position, sort, startersOnly, starredOnly, starred) {
        // Composes with the position filter rather than replacing it, so
        // "starred receivers" is a question you can ask.
        WireBoard.view(entries, position, sort, startersOnly)
            .filter { !starredOnly || it.player.playerId in starred }
    }
    val best = remember(entries) { WireBoard.bestAvailable(entries) }

    LazyColumn(
        state = scrollState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 24.dp)
    ) {
        if (best.isNotEmpty()) {
            item {
                val pager = rememberPagerState(pageCount = { best.size })
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    pageSpacing = 12.dp
                ) { i ->
                    BestAvailable(best[i], onAcquire, best.size, pager.currentPage)
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Chip2("ALL", position == null) { onPosition(null) }
                listOf("QB", "RB", "WR", "TE", "K", "DST").forEach { p ->
                    Chip2(p, position == p) { onPosition(if (position == p) null else p) }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Four now, so they scroll rather than squeeze.
                listOf(
                    BoardSort.PROJECTION to "PROJECTION",
                    BoardSort.OWNED to "% OWNED",
                    BoardSort.LINEUP to "+ LINEUP",
                    BoardSort.TRENDING to "TRENDING"
                ).forEach { (s, label) ->
                    Chip2(label, sort == s) { onSort(s) }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Chip2("\u2605 STARRED", starredOnly) {
                    onStarredOnly?.invoke(!starredOnly)
                }
                Spacer(Modifier.width(6.dp))
                Chip2("STARTERS ONLY", startersOnly) { onStartersOnly(!startersOnly) }
                Spacer(Modifier.width(10.dp))
                Text(
                    "depth rank 1 on their NFL team",
                    style = inkBody(10.0, Ink.mid)
                )
            }
        }

        item {
            Text(
                if (startersOnly && shown.isEmpty())
                    "No available player is first on his NFL depth chart" +
                        (position?.let { " at $it" } ?: "") +
                        ". That is usually the correct answer."
                else "${shown.size} shown of ${b.pool.size} available",
                style = inkBody(10.5, Ink.mid),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("PLAYER", style = inkLabel(8.5, Ink.mid), modifier = Modifier.weight(1f))
                Text("PROJ", style = inkLabel(8.5, Ink.mid),
                    modifier = Modifier.width(46.dp), textAlign = TextAlign.End)
                Text("+LU", style = inkLabel(8.5, Ink.mid),
                    modifier = Modifier.width(46.dp), textAlign = TextAlign.End)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
        }

        itemsIndexed(shown, key = { _, e -> "b-${e.player.playerId}" }) { i, e ->
            BoardRow(e, i, b, onPlayer, onAcquire, e.player.playerId in starred, onStar)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardRow(
    e: BoardEntry,
    index: Int,
    b: Brief,
    onPlayer: (PlayerFocus) -> Unit,
    onAcquire: ((WirePlayer) -> Unit)?,
    isStarred: Boolean = false,
    onStar: ((Int) -> Unit)? = null
) {
    val p = e.player
    val gain = e.lineupGain
    // The tier was only on a small badge and defences had none at all, since
    // they never clear a skill-position line. Tinting the whole card puts the
    // standing where you cannot miss it while scrolling.
    val rank = b.tierNameOf(p.position, p.projection, p.playerId)
    val tier = tierColor(rank)
    val legendary = rank == "LEGENDARY"
    val txt = if (legendary) Fb.LegendaryText else Ink.paper
    val txtDim = if (legendary) Fb.LegendaryText else Ink.mid
    val tierAlpha = when (rank) {
        "LEGENDARY" -> 0.26f
        "ELITE" -> 0.20f
        "SOLID" -> 0.15f
        else -> 0.05f
    }
    val isDst = p.positionId == 16

    Row(
        Modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            // Alternating stripe under the tier tint: the stripe gives the eye
            // a row boundary without a border, and the tint still says what
            // the player is.
            .background(
                if (index % 2 == 0) Color.Transparent
                else Ink.paper.copy(alpha = 0.03f)
            )
            .background(
                tier.copy(alpha = tierAlpha)
            )
            .combinedClickable(
                onClick = { onPlayer(p.focus()) },
                onLongClick = onAcquire?.let { f -> { f(p) } }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(tier))
        Spacer(Modifier.width(12.dp))
        RankedHeadshot(
            p.playerId, p.name, p.ranking?.badge(), p.ranking?.delta,
            36.dp, Ink.accent, isDst, b.proTeams.abbrev(p.proTeamId)
        )
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier.weight(1f)
                .padding(top = 9.dp, bottom = 9.dp, end = 12.dp)
        ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val onWaivers = p.status == "WAIVERS"
                    val statusTint = if (onWaivers) Color(0xFFE0873A) else Ink.positive
                    Box(
                        Modifier.clip(RoundedCornerShape(3.dp))
                            .background(statusTint.copy(alpha = 0.18f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(if (onWaivers) "W" else "FA",
                            style = inkLabel(8.0, statusTint))
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(p.name, style = inkBody(14.0, txt),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, false))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull(e.depthLabel, b.proTeams.abbrev(p.proTeamId))
                            .joinToString(" "),
                        style = inkLabel(8.5, txtDim)
                    )
                    e.tier?.let { t ->
                        Spacer(Modifier.width(6.dp))
                        val tint = tierColor(
                            when (t) {
                                Tier.ELITE -> "ELITE"; Tier.SOLID -> "SOLID"; else -> null
                            }
                        )
                        Box(
                            Modifier.clip(RoundedCornerShape(3.dp))
                                .border(1.dp, tint, RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) { Text(t.label, style = inkLabel(7.5, tint)) }
                    }
                    if (p.percentChange >= 1.0) {
                        Spacer(Modifier.width(6.dp))
                        Text("\u25B2${fmt1(p.percentChange)}",
                            style = inkNum(9.5, Ink.positive))
                    }
                    // A badge, not a line: the note used to wrap and no two
                    // cards ended up the same height.
                    e.note?.let {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(3.dp))
                                .background(Ink.accent.copy(alpha = 0.18f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                if (it.startsWith("Depth")) "STALE" else "MOVING",
                                style = inkLabel(7.5, Ink.accent)
                            )
                        }
                    }
                }
                Text(
                    listOfNotNull(
                        "${fmt0(p.percentOwned)}% owned",
                        b.proTeams.opponent(p.proTeamId, b.league.settings.scoringPeriodId),
                        b.proTeams.kickoffLabel(
                            p.proTeamId, b.league.settings.scoringPeriodId
                        )?.uppercase(),
                    ).joinToString(" \u00B7 "),
                    style = inkNum(9.5, txtDim),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            SplitTotal(p.projection, 15.0, 10.0, txt, TextAlign.End,
                Modifier.width(46.dp))
            Text(
                gain?.let { signed1(it) } ?: "\u2014",
                style = inkNum(13.0, when {
                    gain == null -> Ink.mid
                    gain > 0 -> Ink.positive
                    else -> Ink.mid
                }),
                modifier = Modifier.width(46.dp), textAlign = TextAlign.End
            )
            Box(
                Modifier.width(30.dp).height(44.dp)
                    .clickable(enabled = onStar != null) { onStar?.invoke(p.playerId) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isStarred) "\u2605" else "\u2606",
                    style = inkLabel(13.0, if (isStarred) Ink.positive else Color(0x4D7FB0E0))
                )
            }
        }
        }
    }
}

@Composable
private fun BestAvailable(
    e: BoardEntry,
    onAcquire: ((WirePlayer) -> Unit)?,
    total: Int = 1,
    index: Int = 0
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.positive.copy(alpha = 0.07f))
            .border(1.dp, Ink.positive, RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("BEST AVAILABLE", style = inkLabel(9.5, Ink.positive))
            Spacer(Modifier.weight(1f))
            e.droppedAtMillis?.let {
                val h = (System.currentTimeMillis() - it) / 3_600_000
                Text(if (h < 1) "JUST DROPPED" else "DROPPED ${h}H AGO",
                    style = inkLabel(9.0, Ink.mid))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(e.player.name, style = inkBody(17.0, Ink.paper))
                e.replaces?.let {
                    Text(
                        "Would start over ${it.name} " +
                            "(${fmt1(it.projection ?: 0.0)}).",
                        style = inkBody(12.0, Ink.mid), lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                e.note?.let {
                    Text(it, style = inkBody(11.0, Ink.mid), lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(signed1(e.lineupGain ?: 0.0), style = inkNum(26.0, Ink.positive))
                Text("TO YOUR LINEUP", style = inkLabel(8.0, Ink.mid))
            }
        }
        onAcquire?.let { f ->
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(Ink.positive)
                    .clickable { f(e.player) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (e.player.status == "WAIVERS") "CLAIM OR SCHEDULE" else "ADD NOW",
                    style = inkLabel(11.0, Ink.ground)
                )
            }
        }

        // Dots inside the card: the count belongs to the card, not to a
        // caption underneath it.
        if (total > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 11.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(total) { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp)
                            .size(if (i == index) 6.dp else 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (i == index) Ink.positive
                                else Ink.mid.copy(alpha = 0.45f)
                            )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepthTab(b: Brief, bottomInset: Dp, onPlayer: (PlayerFocus) -> Unit) {
    // Teams worth showing: mine, plus anyone with a player in a real tier.
    val teams = remember(b) {
        (b.league.myTeam?.roster?.map { it.proTeamId }.orEmpty() +
            (b.tiers[Tier.ELITE].orEmpty() + b.tiers[Tier.SOLID].orEmpty())
                .map { it.player.proTeamId })
            .filter { it > 0 }.distinct().sorted()
    }
    val names = remember(b) {
        (b.pool.map { it.playerId to it.name } +
            b.league.teams.flatMap { t -> t.roster.map { it.playerId to it.name } }).toMap()
    }
    val owners = remember(b) {
        b.league.teams.flatMap { t -> t.roster.map { it.playerId to t } }.toMap()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 24.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp,
                    top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MOVES, LAST 72 HOURS", style = inkLabel(11.0, Ink.accent))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Ink.border))
            }
        }

        if (b.depthMoves.isEmpty()) {
            item {
                Text(
                    if (b.depthObservations < 2)
                        "Recording depth charts. A move needs two observations, " +
                            "so this fills in once the app has run again a few " +
                            "hours from now."
                    else "No depth chart moves in the last 72 hours.",
                    style = inkBody(12.0, Ink.mid), lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
        items(b.depthMoves.take(20), key = { "m-${it.playerId}" }) { m ->
            DepthMoveRow(m, b, names[m.playerId], owners[m.playerId]?.name)
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp,
                    top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CURRENT CHARTS", style = inkLabel(11.0, Ink.accent))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Ink.border))
                Spacer(Modifier.width(10.dp))
                Text("${teams.size} TEAMS", style = inkLabel(9.0, Ink.mid))
            }
        }

        items(teams, key = { "t-$it" }) { teamId ->
            TeamChart(teamId, b, names, owners, onPlayer)
        }
    }
}

@Composable
private fun DepthMoveRow(m: DepthMove, b: Brief, name: String?, owner: String?) {
    if (name == null) return   // an unnamed rank tells you nothing
    val hours = (System.currentTimeMillis() - m.observedAtMillis) / 3_600_000
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.width(44.dp)) {
            Text("${m.slot.uppercase()}${m.toRank}",
                style = inkLabel(10.0, if (m.promoted) Ink.positive else Ink.negative))
            Text(if (m.promoted) "\u25B2" else "\u25BC",
                style = inkNum(9.0, if (m.promoted) Ink.positive else Ink.negative))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = inkBody(14.0, Ink.paper))
                Spacer(Modifier.width(7.dp))
                Text(
                    "${b.proTeams.abbrev(m.proTeamId)} \u00B7 " +
                        "${m.slot.uppercase()}${m.fromRank} \u2192 " +
                        "${m.slot.uppercase()}${m.toRank}",
                    style = inkLabel(8.5, Ink.mid)
                )
            }
            Text(
                owner?.let { "Rostered by $it" } ?: "Free agent",
                style = inkBody(10.5, if (owner == null) Ink.positive else Ink.mid),
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Text("${hours}H", style = inkNum(9.5, Ink.mid))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
}

@Composable
private fun TeamChart(
    teamId: Int,
    b: Brief,
    names: Map<Int, String>,
    owners: Map<Int, com.aviato.fantasybrief.data.FantasyTeam>,
    onPlayer: (PlayerFocus) -> Unit
) {
    val chart = b.depth.chartFor(teamId)
    if (chart.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val mine = b.league.myTeam?.roster.orEmpty().filter { it.proTeamId == teamId }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (mine.isNotEmpty()) Ink.tileFill else Color.Transparent)
            .border(1.dp, Ink.border, RoundedCornerShape(9.dp))
            .clickable { open = !open }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.proTeams.abbrev(teamId), style = inkLabel(12.0, Ink.paper),
                modifier = Modifier.width(48.dp))
            Text(
                if (mine.isEmpty()) "no players of yours"
                else mine.joinToString(", ") { it.name },
                style = inkBody(11.0, if (mine.isEmpty()) Ink.mid else Ink.accent),
                modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(if (open) "\u25B4" else "\u25BE", style = inkNum(10.0, Ink.mid))
        }

        if (open) {
            Spacer(Modifier.height(8.dp))
            listOf("qb", "rb", "wr", "te").forEach { slot ->
                val group = chart[slot] ?: return@forEach
                Text(slot.uppercase(), style = inkLabel(9.0, Ink.accent),
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                group.forEach { entry ->
                    val name = names[entry.athleteId] ?: return@forEach
                    val owner = owners[entry.athleteId]
                    val isMine = owner?.id == b.league.myTeamId
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${entry.rank}", style = inkNum(10.0, Ink.mid),
                            modifier = Modifier.width(18.dp))
                        Text(name, style = inkBody(12.5,
                            if (isMine) Ink.accent else Ink.paper),
                            modifier = Modifier.weight(1f))
                        Text(
                            when {
                                isMine -> "YOURS"
                                owner != null -> owner.name.take(14)
                                else -> "FREE"
                            },
                            style = inkLabel(8.0,
                                when {
                                    isMine -> Ink.accent
                                    owner != null -> Ink.mid
                                    else -> Ink.positive
                                })
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip2(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (active) Ink.accent.copy(alpha = 0.20f) else Color.Transparent)
            .border(1.dp, if (active) Ink.accent else Ink.border, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = inkLabel(10.0, if (active) Ink.paper else Ink.mid))
    }
}
