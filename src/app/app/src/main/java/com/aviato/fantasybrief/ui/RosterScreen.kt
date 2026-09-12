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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.EspnClient
import com.aviato.fantasybrief.data.SecretStore
import com.aviato.fantasybrief.data.WeekProjections
import com.aviato.fantasybrief.data.DropCandidate
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.League
import com.aviato.fantasybrief.data.LiveScoreboard
import com.aviato.fantasybrief.data.RosterPlayer
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RosterScreen(
    brief: Brief?,
    loading: Boolean,
    live: LiveScoreboard? = null,
    bottomInset: Dp = 0.dp,
    onPlayer: (PlayerFocus) -> Unit = {},
    onMovePlayer: (RosterPlayer) -> Unit = {},
    state: LeagueScreenState = remember { LeagueScreenState() },
    onRefresh: () -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    /**
     * Player ids flagged as lineup locks this week. Sourced from the
     * analysis rather than scraped: the column publishes weekly and a
     * parser against its markup broke silently.
     */
    lockedPlayerIds: Set<Int> = emptySet()
) {
    // Hoisted so a swipe away does not reset the week you were looking at.
    val overrideTeamId = state.overrideTeamId
    val selectedWeek = state.selectedWeek
    var futureProj by remember { mutableStateOf<Map<Int, Double>>(emptyMap()) }
    var loadingWeek by remember { mutableStateOf(false) }

    if (loading || brief == null) {
        Column(
            Modifier.fillMaxSize().background(Ink.ground),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) { CircularProgressIndicator(color = Ink.accent) }
        return
    }

    val league = brief.league
    val pro = brief.proTeams
    val currentWeek = league.settings.scoringPeriodId
    val week = selectedWeek ?: currentWeek
    val isPast = week < currentWeek
    val isFuture = week > currentWeek
    // A completed week is read from the archive: the lineup that played,
    // frozen, not the roster as it stands now.
    val archive = if (isPast) brief.weekArchives[week] else null

    // ~1.2 MB per week, so fetched only when a future week is selected.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(week, league.id) {
        if (!isFuture) { futureProj = emptyMap(); return@LaunchedEffect }
        loadingWeek = true
        futureProj = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            WeekProjections(EspnClient(SecretStore(context)))
                .load(league.season, league.id, week)
        }
        loadingWeek = false
    }
    val team = league.teams.firstOrNull { it.id == (overrideTeamId ?: league.myTeamId) }
    if (team == null) {
        TeamPicker(league) { state.overrideTeamId = it }
        return
    }

    fun slotOf(p: RosterPlayer): Int =
        archive?.rosters?.get(team.id)?.players
            ?.firstOrNull { it.playerId == p.playerId }?.lineupSlotId
            ?: p.lineupSlotId

    fun valueOf(p: RosterPlayer): Double? = when {
        // Frozen: what he actually scored that week.
        isPast -> archive?.rosters?.get(team.id)?.players
            ?.firstOrNull { it.playerId == p.playerId }?.actual
            ?: p.weeklyActuals[week]
        // A player on bye scores nothing, and the number should say so
        // rather than showing a projection he cannot earn.
        pro.isOnBye(p.proTeamId, week) -> 0.0
        // Real forward projection, or NULL. Falling back to the current
        // week would show a stale number that looks current — a dash is
        // honest and a wrong number is not.
        isFuture -> if (futureProj.isEmpty()) p.projection
                    else futureProj[p.playerId]
        // Current week: once HIS game has kicked off the number becomes what
        // he has actually scored. Before that it stays a projection.
        pro.hasKickedOff(p.proTeamId, week) ->
            live?.points(team.id, p.playerId) ?: p.projection
        else -> p.projection
    }

    /** True once his game is under way, so the tile can show it has run. */
    fun hasPlayed(p: RosterPlayer): Boolean =
        !isFuture && !pro.isOnBye(p.proTeamId, week) &&
            pro.hasKickedOff(p.proTeamId, week)

    val starters = team.roster
        .filter { Enums.isStarterSlot(slotOf(it)) }
        .sortedBy { Enums.slotSortKey(slotOf(it)) }
    val bench = team.roster.filterNot { Enums.isStarterSlot(slotOf(it)) }

    fun medianFor(p: RosterPlayer): Double? =
        brief.replacement.elite(medianPositionFor(p.lineupSlotId, p.position))

    // MUST match Matchup and League. Those sum raw projections, and three
    // screens reporting three totals for one lineup is worse than any of them
    // being slightly wrong. The at-risk figure below carries the nuance
    // instead of hiding it in the headline.
    val projected = starters.sumOf { valueOf(it) ?: 0.0 }

    // What the lineup actually banks if the unavailable players stay out.
    val atRisk = if (isPast) 0.0 else starters
        .filter { !it.healthy || pro.isOnBye(it.proTeamId, week) }
        .sumOf { it.projection ?: 0.0 }
    // A median lineup for THIS slot configuration — a 3-WR league and a 2-WR
    // league get different baselines, correctly.
    val medianLineup = league.settings.starterSlots.sumOf { rule ->
        val pos = medianPositionFor(rule.slotId, Enums.slot(rule.slotId))
        (brief.replacement.elite(pos) ?: 0.0) * rule.count
    }
    val belowMedian = starters.count { p ->
        val m = medianFor(p) ?: return@count false
        (p.projection ?: 0.0) < m
    }

    val byeWeeks = (currentWeek..17).map { w ->
        val out = team.roster.filter { pro.isOnBye(it.proTeamId, w) }
        // K and DST count as bench for severity. A kicker on bye is a
        // two-minute waiver claim, not a roster problem, and counting him as a
        // starter inflates a week where nothing real is wrong — which then
        // buries the weeks where something is.
        val startersOut = out.filter {
            Enums.isStarterSlot(it.lineupSlotId) &&
                it.positionId != 5 && it.positionId != 16
        }
        // A count of players is not actionable. A count of HOLES is: a starter
        // on bye with no eligible healthy bench replacement.
        val holes = startersOut.count { p ->
            bench.none { b ->
                b.eligibleSlots.contains(p.lineupSlotId) &&
                    !pro.isOnBye(b.proTeamId, w) && b.healthy
            }
        }
        ByeWeek(w, startersOut.size, out.size - startersOut.size, holes)
    }
    // Ranked by starters, not by headcount — the bench never costs points.
    val worstBye = byeWeeks.maxByOrNull { it.startersOut }

    Column(Modifier.fillMaxSize().background(Ink.ground)) {
        header?.invoke()
        TeamMasthead(team.name, team.logoUrl, projected, medianLineup,
            belowMedian, starters.size, atRisk)

        LazyColumn(
            state = state.rosterScroll,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 28.dp)
        ) {
            item { TeamSection("BYE EXPOSURE", "STARTERS OUT \u00B7 +BENCH") }
            item {
                ByeRow(byeWeeks, currentWeek, week) { state.selectedWeek = it }
            }
            item {
                Text(
                    when {
                        isPast -> "Week $week, final. Frozen as it was played."
                        isFuture -> if (loadingWeek) "Week $week \u2014 loading..."
                            else if (futureProj.isNotEmpty())
                                "Week $week projections, from ESPN."
                            else "Week $week. Projections unavailable; showing " +
                                "week $currentWeek numbers."
                        else -> ""
                    },
                    style = inkBody(10.5, Ink.accent),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item {
                Text(
                    worstBye?.takeIf { it.total > 0 }?.let { b ->
                        buildString {
                            append("Week ${b.week} is your worst: ")
                            append("${b.startersOut} starter(s)")
                            if (b.benchOut > 0) append(" and ${b.benchOut} on the bench")
                            append(" out. ")
                            append(
                                when {
                                    b.startersOut == 0 ->
                                        "No starters affected, so nothing to do."
                                    b.holes > 0 ->
                                        "${b.holes} starting slot(s) have no eligible " +
                                            "replacement on your bench."
                                    else -> "Every affected slot has cover on your bench."
                                }
                            )
                        }
                    } ?: "No week takes a player off the board.",
                    style = inkBody(12.0, when {
                        (worstBye?.holes ?: 0) > 0 -> Ink.negative
                        (worstBye?.startersOut ?: 0) >= 3 -> Ink.negative
                        else -> Ink.mid
                    }),
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            item { TeamSection("STARTERS", "BAR MARK = LEAGUE MEDIAN") }
            items(starters, key = { "start-${it.playerId}" }) { p ->
                RosterTile(
                    p, brief, week, true, valueOf(p), slotOf(p),
                    hasPlayed = hasPlayed(p),
                    locked = p.playerId in lockedPlayerIds,
                    onTap = { onPlayer(p.focus(team.id)) },
                    onHold = { onMovePlayer(p) }
                )
            }

            item { TeamSection("BENCH", "HOLD TO SWAP") }
            items(bench, key = { "bench-${it.playerId}" }) { p ->
                RosterTile(
                    p, brief, week, false, valueOf(p), slotOf(p),
                    hasPlayed = hasPlayed(p),
                    locked = p.playerId in lockedPlayerIds,
                    onTap = { onPlayer(p.focus(team.id)) },
                    onHold = { onMovePlayer(p) }
                )
            }



            item { TeamSection("WHAT COUNTS AS STARTABLE HERE", "FROM ALL LINEUPS") }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text("POS", style = inkLabel(8.5, Ink.mid),
                        modifier = Modifier.width(48.dp))
                    listOf("YOURS", "MEDIAN", "25TH").forEach {
                        Text(it, style = inkLabel(8.5, Ink.mid),
                            modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                    Text("SLOTS", style = inkLabel(8.5, Ink.mid),
                        modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
                }
            }
            items(brief.replacement.positions, key = { "repl-${it.position}" }) { lv ->
                val mine = starters.filter {
                    medianPositionFor(it.lineupSlotId, it.position) == lv.position
                }
                ReplacementRow(
                    position = lv.position,
                    yours = mine.mapNotNull { it.projection }.average()
                        .takeIf { !it.isNaN() },
                    median = lv.eliteLine,
                    p25 = lv.solidLine,
                    slots = lv.sampleSize
                )
            }
        }
    }
}

@Composable
private fun TeamMasthead(
    name: String, logoUrl: String?,
    projected: Double, medianLineup: Double,
    belowMedian: Int, starterCount: Int, atRisk: Double
) {
    val delta = projected - medianLineup
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp,
                top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TeamLogo(logoUrl, name, 58.dp, Ink.accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name.uppercase(), style = inkLabel(13.0, Ink.mid),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(9.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .border(1.dp, if (delta >= 0) Ink.positive else Ink.negative,
                                RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(signed1(delta), style = inkNum(10.5,
                            if (delta >= 0) Ink.positive else Ink.negative))
                    }
                }
                SplitTotal(projected, 36.0, 18.0, Ink.paper, TextAlign.Start)
                Text(
                    "projected \u00B7 ${two(medianLineup)} for a median lineup",
                    style = inkNum(9.5, Ink.mid)
                )
                if (atRisk > 0.0) {
                    Text(
                        "${two(atRisk)} of that is in unavailable starters",
                        style = inkNum(9.5, Ink.negative)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(belowMedian.toString(),
                    style = inkNum(17.0,
                        if (belowMedian > 0) Ink.accent else Ink.mid))
                Text("BELOW MEDIAN", style = inkLabel(8.5, Ink.mid))
                Text("of $starterCount", style = inkLabel(8.0, Ink.mid))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RosterTile(
    p: RosterPlayer,
    brief: Brief,
    week: Int,
    isStarter: Boolean,
    shownValue: Double?,
    slotId: Int,
    onTap: () -> Unit,
    onHold: () -> Unit,
    /** His game has kicked off, so the number is a result not a forecast. */
    hasPlayed: Boolean = false,
    /** Flagged as a lineup lock in this week's analysis. */
    locked: Boolean = false
) {
    val pro = brief.proTeams
    val median = brief.replacement.elite(medianPositionFor(slotId, p.position))
    val proj = shownValue
    val onBye = pro.isOnBye(p.proTeamId, week)
    val delta = if (median != null && proj != null) proj - median else null
    val unavailable = !p.healthy && (p.injuryTag == "O" || p.injuryTag == "IR")
    // A defence has no face and its own logo is the identity.
    val isDst = p.positionId == 16
    val tierName = brief.tierNameOf(p.position, shownValue, p.playerId)
    val legendary = tierName == "LEGENDARY"
    val txt = if (legendary) Fb.LegendaryText else Ink.paper
    val txtDim = if (legendary) Fb.LegendaryText else Ink.mid
    val txtAccent = if (legendary) Fb.LegendaryText else Ink.accent

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
            .then(if (!isStarter) Modifier.alpha(0.70f) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (isStarter) Enums.slot(slotId)
            else if (slotId == 21) "IR" else "BN",
            style = inkLabel(10.0, if (isStarter) Ink.accent else Ink.mid),
            modifier = Modifier.width(30.dp), textAlign = TextAlign.Center
        )
        Row(
            Modifier.weight(1f)
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    tierFill(
                        tierName,
                        when {
                            onBye -> Ink.negative.copy(alpha = 0.10f)
                            // Played: a lighter wash, so a finished tile reads
                            // as settled rather than pending.
                            hasPlayed -> Ink.paper.copy(alpha = 0.13f)
                            isStarter -> Ink.accent.copy(alpha = 0.055f)
                            else -> Ink.paper.copy(alpha = 0.03f)
                        }
                    )
                )
                .border(
                    tierBorderWidth(tierName).dp,
                    tierBorder(
                        tierName,
                        if (unavailable || onBye) Ink.negative else Ink.border
                    ),
                    RoundedCornerShape(9.dp))
                .combinedClickable(onClick = onTap, onLongClick = onHold)
        ) {
            Box(
                Modifier.width(3.dp).fillMaxHeight()
                    .background(tierColor(tierName))
            )

            // The face sits OUTSIDE the content column, so the bar below
            // starts after it rather than running under it — which is what
            // lets the picture be this size without wasting the row.
            Box(Modifier.padding(start = 9.dp, top = 9.dp, bottom = 9.dp)) {
                RankedHeadshot(
                    p.playerId, p.name,
                    brief.rankings[p.playerId]?.badge(),
                    brief.rankings[p.playerId]?.delta,
                    44.dp, Ink.accent, isDst, pro.abbrev(p.proTeamId)
                )
            }
            Spacer(Modifier.width(10.dp))

            Column(
                Modifier.weight(1f).padding(end = 9.dp, top = 9.dp, bottom = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Logo sized to the name-plus-subtitle block, so the three
                    // read as one unit rather than three stacked things.
                    if (!isDst) {
                        ProTeamLogo(pro.abbrev(p.proTeamId), 30.dp, Ink.accent)
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = inkBody(14.0, txt),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, false))
                            // Only on the bench: a starter's slot column
                            // already says the position, and repeating it
                            // would be noise.
                            if (!isStarter) {
                                Spacer(Modifier.width(6.dp))
                                Text(p.position, style = inkLabel(9.0, txtAccent))
                            }

                            if (!p.healthy) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier.clip(RoundedCornerShape(3.dp))
                                        .border(1.dp,
                                            if (unavailable) Ink.negative else Ink.accent,
                                            RoundedCornerShape(3.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(p.injuryTag, style = inkLabel(8.0,
                                        if (unavailable) Ink.negative else Ink.accent))
                                }
                            }
                        }
                        Text(
                            listOfNotNull(
                                if (onBye) "BYE" else pro.opponent(p.proTeamId, week),
                                pro.kickoffLabel(p.proTeamId, week)?.uppercase(),
                                p.pointsPerGame?.let { "season ${fmt1(it)}" }
                            ).joinToString(" \u00B7 "),
                            style = inkNum(9.0, txtDim), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        SplitTotal(proj, 19.0, 12.0, txt, TextAlign.End)
                        Text(
                            if (delta != null && median != null)
                                "${signed1(delta)} vs ${fmt1(median)}"
                            else "no median",
                            style = inkNum(9.0, when {
                                delta == null -> Ink.mid
                                delta >= 0 -> Ink.positive
                                else -> Ink.accent.copy(alpha = 0.75f)
                            })
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The gap is reserved whether or not there is a lock, so
                    // every bar is the same length — a bar that changed width
                    // with lock status would read as a different value.
                    Box(Modifier.weight(1f)) { MedianBar(proj, median) }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(15.dp)) {
                        if (locked) LockBadge(15.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DropRow(c: DropCandidate, brief: Brief, onTap: () -> Unit) {
    val p = c.player
    val median = brief.replacement.elite(p.position)
    val below = median != null && (p.projection ?: 0.0) < median
    Row(
        Modifier.fillMaxWidth().clickable { onTap() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(p.projection?.let { fmt1(it) } ?: "\u2014",
            style = inkNum(14.0, if (below) Ink.negative else Ink.paper),
            modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = inkBody(14.0, Ink.paper))
            Text(
                "${p.position} \u00B7 ${brief.proTeams.abbrev(p.proTeamId)}" +
                    (median?.let {
                        " \u00B7 proj ${fmt1(p.projection ?: 0.0)} vs ${fmt1(it)} median"
                    } ?: ""),
                style = inkNum(9.5, Ink.mid), modifier = Modifier.padding(top = 2.dp)
            )
            if (c.flags.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Every flag renders identically, including the ones that
                    // argue AGAINST dropping him — the row must not read as a
                    // recommendation either way.
                    c.flags.forEach { f ->
                        Box(
                            Modifier.clip(RoundedCornerShape(3.dp))
                                .border(1.dp, Ink.border, RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) { Text(f, style = inkLabel(8.0, Ink.mid)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamSection(title: String, trailing: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = inkLabel(11.5, Ink.accent))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Ink.border))
        trailing?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, style = inkLabel(9.0, Ink.mid))
        }
    }
}

@Composable
private fun TeamPicker(league: League, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink.ground)) {
        Masthead("Pick your team", "Your ESPN id didn't match an owner here")
        LazyColumn {
            items(league.teams, key = { it.id }) { team ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(team.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) { Text(team.name, style = inkBody(15.0, Ink.paper)) }
            }
        }
    }
}
