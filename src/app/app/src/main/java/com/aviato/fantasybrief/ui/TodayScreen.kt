package com.aviato.fantasybrief.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.ActivityLog
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.DumpBuilder
import com.aviato.fantasybrief.data.DumpBuilder2
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.PlayerUpdate
import com.aviato.fantasybrief.data.PowerRankings
import com.aviato.fantasybrief.data.Tier
import com.aviato.fantasybrief.data.TodayFeed
import com.aviato.fantasybrief.data.TradeState
import com.aviato.fantasybrief.data.Transaction
import com.aviato.fantasybrief.data.TxKind
import com.aviato.fantasybrief.data.WireAlert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    brief: Brief?,
    loading: Boolean,
    error: String?,
    bottomInset: Dp,
    onRefresh: () -> Unit,
    onResetSnapshot: () -> Unit,
    onSwitchLeague: () -> Unit = {},
    onPlayer: (PlayerFocus) -> Unit = {},
    onMovePlayer: ((com.aviato.fantasybrief.data.RosterPlayer) -> Unit)? = null,
    onSwapTo: ((com.aviato.fantasybrief.data.RosterPlayer,
        com.aviato.fantasybrief.data.RosterPlayer) -> Unit)? = null,
    remoteInsights: com.aviato.fantasybrief.data.InsightPayload? = null,
    onOpenMatchup: () -> Unit = {},
    onOpenWire: () -> Unit = {},
    onAcquire: ((com.aviato.fantasybrief.data.WirePlayer) -> Unit)? = null,
    // Hoisted by the caller. Owning it here would not survive the tab
    // switch, because `when (tab)` disposes this whole composable.
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    state: LeagueScreenState = remember { LeagueScreenState() },
    global: GlobalScreenState = remember { GlobalScreenState() }
) {
    // Dismissals live for the life of the composition only — deliberately no
    // store, no expiry rules, no return policy.
    // Hoisted: all of this was disposed on a swipe away.
    val dismissed = state.dismissed
    val highlight = state.highlightTeam
    val txFilter = global.txFilter
    val txExpanded = state.txExpanded
    val standingsExpanded = state.standingsExpanded
    val atRiskOpen = state.atRiskOpen
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (loading && brief == null) {
        Column(
            Modifier.fillMaxSize().background(Ink.ground),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) { CircularProgressIndicator(color = Ink.accent) }
        return
    }
    if (error != null && brief == null) {
        Column(Modifier.fillMaxSize().background(Ink.ground).padding(20.dp)) {
            Text(error, style = inkBody(14.0, Ink.negative))
            Spacer(Modifier.height(14.dp))
            OutlineAction("Try again", Ink.accent) { onRefresh() }
        }
        return
    }
    val b = brief ?: return

    val league = b.league
    val week = league.settings.scoringPeriodId
    val myId = league.myTeamId
    val hasResults = PowerRankings.hasResults(league)
    val plotted = highlight ?: myId ?: b.standings.firstOrNull()?.team?.id ?: 0

    val opponentId = b.matchups.firstOrNull {
        it.homeTeamId == myId || it.awayTeamId == myId
    }?.let { if (it.homeTeamId == myId) it.awayTeamId else it.homeTeamId }

    val top = remember(b) {
        TodayFeed.topAction(league, b.proTeams, b.replacement, b.flags, week)
    }

    LaunchedEffect(top?.headline) {
        val headline = top?.headline
        if (headline != null && headline != state.lastSeenTopAction) {
            state.doFirstOpen = true
            state.lastSeenTopAction = headline
        }
    }
    val updates = remember(b) {
        TodayFeed.playerUpdates(
            league, b.wire, b.proTeams, b.depth, b.replacement,
            b.beneficiaries, week, opponentId
        )
    }.filterNot { it.playerId in dismissed }
    val atRisk = b.atRisk
    val alerts = remember(b) {
        TodayFeed.wireAlerts(league, b.tiers, b.transactions, b.depth, b.playerNames, week)
    }

    Column(Modifier.fillMaxSize().background(Ink.ground)) {
        TodayMasthead(b, onSwitchLeague, onRefresh, loading)

        // Two panes: what changed, and what the analysts think. Insights is a
        // weekly read rather than a daily one, so it sits behind a sub-tab
        // instead of taking permanent nav space.
        Row(Modifier.fillMaxWidth()) {
            listOf("TODAY", "INSIGHTS").forEachIndexed { i, label ->
                Column(
                    Modifier.weight(1f).clickable { state.todayPane = i },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        label,
                        style = inkLabel(
                            11.0,
                            if (state.todayPane == i) Ink.accent else Ink.mid
                        ),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    Box(
                        Modifier.fillMaxWidth().height(2.dp).background(
                            if (state.todayPane == i) Ink.accent else Color.Transparent
                        )
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))

        if (state.todayPane == 1) {
            InsightsPane(b, remoteInsights, bottomInset, onPlayer)
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 28.dp)
        ) {
            // ---- 1. one action, always exactly one -----------------------
            item {
                if (top == null) {
                    Text(
                        "Nothing to act on. Next waiver run " +
                            "${league.settings.waiverHours}h cycle, Mon 11:00.",
                        style = inkBody(13.0, Ink.mid),
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    DoThisFirst(
                        top,
                        open = state.doFirstOpen,
                        onToggle = { state.doFirstOpen = !state.doFirstOpen },
                        onFix = {
                            // The card already named the pairing, so go straight
                            // to review rather than reopening an empty picker.
                            val subject = top.subject
                            val target = top.fixTarget
                            when {
                                top.acquireTarget != null ->
                                    onAcquire?.invoke(top.acquireTarget!!)
                                subject != null && target != null ->
                                    onSwapTo?.invoke(subject, target)
                                subject != null -> onMovePlayer?.invoke(subject)
                            }
                        },
                        // Options is the escape hatch: pick someone else.
                        onOptions = {
                            top.subject?.let { s -> onMovePlayer?.invoke(s) }
                                ?: onOpenWire()
                        }
                    )
                }
            }

            // ---- AT RISK: starters who may not play, with a free backup
            if (atRisk.isNotEmpty()) {
                val elite = atRisk.filter {
                    b.replacement.rank(it.starter.position, it.starter.projection) == "ELITE"
                }
                // Gold means an elite starter is not merely questionable but
                // doubtful or worse — the case where a job is genuinely opening.
                val gold = elite.any { it.certainty >= 70 }
                item {
                    AtRiskHeader(
                        count = atRisk.size,
                        mine = atRisk.count { it.isMine },
                        eliteCount = elite.size,
                        gold = gold,
                        open = atRiskOpen,
                        topLine = atRisk.firstOrNull()?.let {
                            "${it.starter.name} \u2192 ${it.backup.name}"
                        } ?: "",
                        onToggle = { state.atRiskOpen = !state.atRiskOpen }
                    )
                }
                if (atRiskOpen) {
                    items(atRisk, key = { "ar-${it.backup.playerId}" }) { pair ->
                        AtRiskCard(pair, b, onPlayer) { w -> onAcquire?.invoke(w) }
                    }
                }
            }

            // ---- 2. power --------------------------------------------------
            if (b.powerChartable) {
                item { InkSectionRow("POWER", "2-WEEK AVERAGE") }
                item {
                    PowerChart(
                        series = b.powerSeries,
                        highlightTeamId = plotted,
                        highlightName = league.teams.firstOrNull { it.id == plotted }?.name
                            ?: "",
                        isMine = plotted == myId
                    )
                }
            }

            // ---- 3. standings ---------------------------------------------
            item {
                InkSectionRow("STANDINGS",
                    if (b.powerChartable) "TAP TO PLOT"
                    else if (hasResults) null else "PRESEASON")
            }
            item {
                StandingsTable(
                    b.standings, myId, plotted, hasResults,
                    expanded = standingsExpanded,
                    onToggle = { state.standingsExpanded = !state.standingsExpanded },
                    onHighlight = { state.highlightTeam = it }
                )
            }

            // ---- 4. player updates -----------------------------------------
            if (updates.isNotEmpty()) {
                item { InkSectionRow("PLAYER UPDATES", "EFFECT ON YOU") }
                items(updates, key = { "upd-${it.playerId}-${it.relation}" }) { u ->
                    UpdateCard(
                        u, b,
                        onAction = {
                            when (u.relation) {
                                "IN YOUR LINEUP", "ON YOUR BENCH" ->
                                    u.player?.let { p -> onMovePlayer?.invoke(p) }
                                "HELPS YOU" -> onOpenMatchup()
                                else -> onOpenWire()
                            }
                        },
                        onDetail = {
                            u.player?.let { onPlayer(it.focus(myId ?: -1)) }
                                ?: u.wirePlayer?.let { onPlayer(it.focus()) }
                        },
                        onDismiss = { state.dismissed = state.dismissed + u.playerId }
                    )
                }
            }

            // ---- 5. wire alerts --------------------------------------------
            if (alerts.isNotEmpty()) {
                item {
                    InkSectionRow(
                        "WIRE ALERTS",
                        "WAIVER ${league.myTeam?.waiverRank ?: "-"} \u00B7 MON 11:00"
                    )
                }
                items(alerts, key = { "alert-${it.player.playerId}" }) { a ->
                    WireAlertCard(a, b, onClaim = onOpenWire,
                        onCompare = { onPlayer(a.player.focus()) })
                }
            }

            // ---- 6. transactions -------------------------------------------
            item { TransactionsHeader(b, txFilter) { global.txFilter = it } }
            // This week only — the full history lives in the dump, and a
            // scrolling season of moves is not what this screen is for.
            val weekStart = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            val roster = b.transactions
                .filter { it.kind != TxKind.LINEUP && it.kind != TxKind.UNKNOWN }
                .filter { it.whenMillis >= weekStart }
                .filter { txFilter == null || it.kind == txFilter }

            // A manager's add and the drop that paid for it arrive as separate
            // messages seconds apart. Grouping them reads as one decision,
            // which is what it was.
            val groups = buildList {
                var current = mutableListOf<Transaction>()
                roster.forEach { tx ->
                    val head = current.firstOrNull()
                    if (head != null && head.teamId == tx.teamId &&
                        kotlin.math.abs(head.whenMillis - tx.whenMillis) < 120_000
                    ) current.add(tx)
                    else {
                        if (current.isNotEmpty()) add(current.toList())
                        current = mutableListOf(tx)
                    }
                }
                if (current.isNotEmpty()) add(current.toList())
            }
            val visible = if (txExpanded) groups else groups.take(3)

            items(visible, key = { "tx-${it.first().id}" }) { group ->
                TransactionGroup(group, b)
            }
            if (groups.size > 3) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { state.txExpanded = !state.txExpanded }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            if (txExpanded) "SHOW LESS"
                            else "ALL ${groups.size} THIS WEEK  \u25BE",
                            style = inkLabel(9.5, Ink.accent)
                        )
                    }
                }
            }

            // ---- 7. dump, and diagnostics below it -------------------------
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("FOR CLAUDE", style = inkLabel(9.5, Ink.mid))
                    Spacer(Modifier.height(8.dp))
                    OutlineAction(
                        if (copied) "Copied" else "Copy daily brief",
                        Ink.accent, fill = true
                    ) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText("daily", DumpBuilder2.daily(b))
                        )
                        copied = true
                    }
                    Text(
                        "What changed and what to do about it. Paste any time.",
                        style = inkBody(10.5, Ink.mid),
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
                    )
                    // Equal weight so they read as a pair rather than one
                    // button with an orphan beside it.
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            OutlineAction("Weekly review", Ink.mid) {
                                val cm = context.getSystemService(
                                    Context.CLIPBOARD_SERVICE
                                ) as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText("weekly", DumpBuilder2.weekly(b))
                                )
                                copied = true
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            OutlineAction("Full dump", Ink.mid) {
                                val cm = context.getSystemService(
                                    Context.CLIPBOARD_SERVICE
                                ) as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText("full", DumpBuilder.build(b))
                                )
                                copied = true
                            }
                        }
                    }
                    Text(
                        "Weekly: results and planning, run after waivers. " +
                            "Full: everything, mostly superseded.",
                        style = inkBody(10.0, Ink.mid.copy(alpha = 0.7f)),
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    b.notes.forEach {
                        Text(it, style = inkBody(10.5, Ink.mid),
                            modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

/**
 * When the data arrived, in words.
 *
 * A refresh that completes in under a second is invisible — the diff window
 * does not move, the numbers rarely move, and the button flickers. Saying
 * "updated just now" is the only proof the tap did anything.
 */
private fun freshness(fetchedAt: Long, tookMillis: Long): String {
    val age = (System.currentTimeMillis() - fetchedAt) / 1000
    val stamp = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
        .format(java.util.Date(fetchedAt))
        .replace("AM", "am").replace("PM", "pm")
    return when {
        age < 60 -> "Updated just now \u00B7 ${tookMillis}ms"
        age < 3600 -> "Updated ${age / 60}m ago at $stamp"
        else -> "Updated at $stamp"
    }
}

@Composable
private fun AtRiskHeader(
    count: Int,
    mine: Int,
    eliteCount: Int,
    gold: Boolean,
    open: Boolean,
    topLine: String,
    onToggle: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (gold) Color(0xFFE8B44A).copy(alpha = 0.10f) else Ink.tileFill
            )
            .border(
                1.dp,
                if (gold) Color(0xFFE8B44A) else Ink.border,
                RoundedCornerShape(10.dp)
            )
            .clickable { onToggle() }
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (gold) {
                Text("\u2605", style = inkNum(15.0, Color(0xFFE8B44A)))
                Spacer(Modifier.width(7.dp))
            } else if (eliteCount > 0) {
                Text("\u2605", style = inkNum(13.0, Fb.TierElite))
                Spacer(Modifier.width(7.dp))
            }
            Text("AT RISK", style = inkLabel(11.5, Ink.accent))
            Spacer(Modifier.weight(1f))
            Text(if (open) "\u25B4" else "\u25BE", style = inkNum(11.0, Ink.mid))
        }
        Text(
            buildString {
                append(count).append(" starter").append(if (count > 1) "s" else "")
                append(" flagged")
                if (mine > 0) append(", ").append(mine).append(" of yours")
                if (eliteCount > 0) {
                    append(" \u00B7 ").append(eliteCount).append(" elite")
                }
            },
            style = inkBody(12.5, Ink.paper),
            modifier = Modifier.padding(top = 6.dp)
        )
        if (!open && topLine.isNotBlank()) {
            Text(topLine, style = inkBody(11.0, Ink.mid),
                modifier = Modifier.padding(top = 3.dp))
        }
        if (gold) {
            Text(
                "An elite starter is doubtful or worse and his backup is unowned.",
                style = inkBody(10.5, Color(0xFFE8B44A)), lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TodayMasthead(
    b: Brief, onSwitch: () -> Unit, onRefresh: () -> Unit, loading: Boolean
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp,
                top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.clickable { onSwitch() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(b.league.settings.name.uppercase(),
                        style = inkLabel(19.0, Ink.paper), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("  \u25BE", style = inkLabel(14.0, Ink.accent))
                }
                Text(
                    "Week ${b.league.settings.currentMatchupPeriod} \u00B7 " +
                        "${b.league.settings.size} teams \u00B7 " +
                        (b.baselineAgeMinutes?.let {
                            if (it < 90) "changes over ${it}m"
                            else "changes over ${it / 60}h"
                        } ?: "building history"),
                    style = inkBody(11.5, Ink.mid)
                )
                Text(
                    freshness(b.fetchedAtMillis, b.millis),
                    style = inkBody(10.5, Ink.accent),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(5.dp))
                    .background(
                        if (loading) Ink.accent.copy(alpha = 0.35f)
                        else Ink.accent.copy(alpha = 0.18f)
                    )
                    .clickable(enabled = !loading) { onRefresh() }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    if (loading) "UPDATING" else "REFRESH",
                    style = inkLabel(11.0, if (loading) Ink.paper else Ink.accent)
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
    }
}

@Composable
private fun DoThisFirst(
    top: com.aviato.fantasybrief.data.TopAction,
    open: Boolean,
    onToggle: () -> Unit,
    onFix: () -> Unit,
    onOptions: () -> Unit
) {
    // Red when it costs me points; green when it is an opportunity on
    // someone else's roster.
    val edge = when {
        top.acquireTarget != null && !top.mineAtRisk -> Ink.positive
        top.urgent -> Ink.negative
        else -> Ink.accent
    }
    Column(
        Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(edge.copy(alpha = 0.07f))
            .border(1.dp, edge, RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("DO THIS FIRST", style = inkLabel(10.0, edge))
            Spacer(Modifier.weight(1f))
            listOfNotNull(top.ageText, top.deadlineText).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" \u00B7 "), style = inkLabel(9.5, Ink.mid))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (open) "\u25B4" else "\u25BE", style = inkNum(11.0, Ink.mid))
        }
        Spacer(Modifier.height(8.dp))
        // Headline always visible — collapsing it would leave a card that
        // says nothing.
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(top.headline, style = inkBody(15.0, Ink.paper), maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                if (open) {
                    Text(top.body, style = inkBody(12.0, Ink.mid),
                        lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
            if (top.pointSwing != 0.0) {
                Column(
                    Modifier.padding(start = 12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(signed1(top.pointSwing),
                        style = inkNum(26.0,
                            if (top.pointSwing >= 0) Ink.positive else Ink.negative))
                    Text("PROJECTED", style = inkLabel(8.5, Ink.mid))
                }
            }
        }
        if (top.alsoSee.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
            Spacer(Modifier.height(8.dp))
            top.alsoSee.forEach {
                Text("Also: $it", style = inkBody(11.5, Ink.mid), lineHeight = 16.sp)
            }
        }

        if (open) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val acquire = top.acquireTarget
            if (acquire != null) {
                Box(Modifier.weight(1f)) {
                    OutlineAction(
                        if (acquire.status == "WAIVERS")
                            "Claim ${acquire.name.substringAfterLast(' ')}"
                        else "Add ${acquire.name.substringAfterLast(' ')} now",
                        Ink.positive, fill = true, onClick = onFix
                    )
                }
                OutlineAction("Wire", Ink.accent, onClick = onOptions)
            } else {
                top.fixLabel?.let { label ->
                    Box(Modifier.weight(1f)) {
                        OutlineAction(
                            "$label \u00B7 ${signed1(top.pointSwing)}",
                            Ink.positive, fill = true, onClick = onFix
                        )
                    }
                }
                OutlineAction("Someone else", Ink.accent, onClick = onOptions)
            }
        }
        }
    }
}

@Composable
private fun UpdateCard(
    u: PlayerUpdate,
    b: Brief,
    onAction: () -> Unit,
    onDetail: () -> Unit,
    onDismiss: () -> Unit
) {
    val costs = u.effect < 0
    val edge = if (costs) Ink.negative else Ink.border
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (costs) Ink.negative.copy(alpha = 0.06f) else Ink.tileFill)
            .border(1.dp, edge, RoundedCornerShape(9.dp))
            .padding(13.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            RankedHeadshot(
                u.playerId, u.name, b.rankings[u.playerId]?.badge(),
                b.rankings[u.playerId]?.delta,
                38.dp, if (costs) Ink.negative else Ink.accent
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.name, style = inkBody(14.5, Ink.paper))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull(u.depthLabel, b.proTeams.abbrev(u.proTeamId))
                            .joinToString(" "),
                        style = inkLabel(9.0, Ink.mid)
                    )
                }
                Text(u.what, style = inkBody(12.0, Ink.mid),
                    lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(signed1(u.effect),
                    style = inkNum(17.0, if (costs) Ink.negative else Ink.positive))
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(3.dp))
                        .border(1.dp, if (costs) Ink.negative else Ink.accent,
                            RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(u.relation,
                        style = inkLabel(8.0, if (costs) Ink.negative else Ink.accent))
                }
            }
        }
        Spacer(Modifier.height(11.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlineAction(u.actionLabel, if (costs) Ink.negative else Ink.accent,
                onClick = onAction)
            Spacer(Modifier.width(8.dp))
            OutlineAction("Detail", Ink.mid, onClick = onDetail)
            Spacer(Modifier.weight(1f))
            Text("DISMISS", style = inkLabel(9.5, Ink.mid),
                modifier = Modifier.clickable { onDismiss() }.padding(6.dp))
        }
    }
}

@Composable
private fun WireAlertCard(
    a: WireAlert, b: Brief,
    onClaim: () -> Unit, onCompare: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(9.dp)).background(Ink.tileFill)
            .border(1.dp, Ink.border, RoundedCornerShape(9.dp)).padding(13.dp)
    ) {
        a.blunder?.let { bl ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(3.dp))
                        .background(Ink.positive.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text("DROPPED", style = inkLabel(8.5, Ink.positive)) }
                Spacer(Modifier.width(7.dp))
                Text(
                    "by ${bl.droppedBy}, ${
                        if (bl.hoursAgo < 1) "under an hour" else "${bl.hoursAgo}h"
                    } ago" + if (bl.wouldStart) " \u00B7 would start for you" else "",
                    style = inkBody(11.0, Ink.positive)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            RankedHeadshot(
                a.player.playerId, a.player.name,
                a.player.ranking?.badge(), a.player.ranking?.delta,
                38.dp, Ink.accent
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.player.name, style = inkBody(14.5, Ink.paper))
                    Spacer(Modifier.width(7.dp))
                    // App-wide tier colours — green here would collide with
                    // positive, which means a comparison, not a standing.
                    val tint = tierColor(
                        when (a.tier) {
                            Tier.ELITE -> "ELITE"
                            Tier.SOLID -> "SOLID"
                            else -> null
                        }
                    )
                    Box(
                        Modifier.clip(RoundedCornerShape(3.dp))
                            .border(1.dp, tint, RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) { Text(a.tier.label, style = inkLabel(8.0, tint)) }
                }
                Text(
                    "${a.player.position} \u00B7 ${b.proTeams.abbrev(a.player.proTeamId)} \u00B7 " +
                        "${fmt0(a.player.percentOwned)}% owned " +
                        signed1(a.player.percentChange),
                    style = inkLabel(9.5, Ink.mid),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(signed1(a.gain), style = inkNum(17.0, Ink.positive))
                Text("PROJ ${fmt1(a.player.projection ?: 0.0)}",
                    style = inkLabel(8.5, Ink.mid))
            }
        }
        Text(a.whyNow, style = inkBody(12.0, Ink.paper), lineHeight = 17.sp,
            modifier = Modifier.padding(top = 9.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border)
            .padding(top = 10.dp))
        Spacer(Modifier.height(9.dp))
        Text("over ${a.overName} (${fmt1(a.overProjection)}), ${a.overDescription}",
            style = inkBody(11.5, Ink.mid))
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineAction("Claim", Ink.positive, fill = true, onClick = onClaim)
            OutlineAction("Compare", Ink.accent, onClick = onCompare)
        }
    }
}

@Composable
private fun TransactionsHeader(b: Brief, active: TxKind?, onFilter: (TxKind?) -> Unit) {
    val weekStart = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
    val counts = b.transactions
        .filter { it.kind != TxKind.LINEUP && it.kind != TxKind.UNKNOWN }
        .filter { it.whenMillis >= weekStart }
        .groupingBy { it.kind }.eachCount()
    Column {
        InkSectionRow("TRANSACTIONS", "LAST 7 DAYS")
        Text(
            counts.entries.filter { it.value > 0 }
                .joinToString(" \u00B7 ") { "${it.value} ${it.key.name.lowercase()}" },
            style = inkBody(11.5, Ink.mid),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Chip("All ${counts.values.sum()}", active == null) { onFilter(null) }
            // Zero-count chips are hidden, not disabled.
            TxKind.entries
                .filter { it != TxKind.LINEUP && it != TxKind.UNKNOWN }
                .filter { (counts[it] ?: 0) > 0 }.forEach { k ->
                Chip("${k.name.lowercase().replaceFirstChar { c -> c.uppercase() }} " +
                    "${counts[k]}", active == k) { onFilter(k) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text("WHEN", style = inkLabel(8.5, Ink.mid), modifier = Modifier.width(30.dp))
            Text("TEAM \u00B7 PLAYERS \u00B7 CONSEQUENCE", style = inkLabel(8.5, Ink.mid),
                modifier = Modifier.weight(1f))
            Text("TYPE", style = inkLabel(8.5, Ink.mid), modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
    }
}

@Composable
private fun TransactionGroup(group: List<Transaction>, b: Brief) {
    val head = group.first()
    val mine = head.teamId == b.league.myTeamId
    val teamName = b.league.teams.firstOrNull { it.id == head.teamId }?.name
        ?: "team ${head.teamId}"

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (mine) Ink.tileFill else Color.Transparent)
            .border(1.dp, Ink.border, RoundedCornerShape(9.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(teamName.uppercase(),
                style = inkLabel(9.5, if (mine) Ink.accent else Ink.mid),
                modifier = Modifier.weight(1f))
            Text(ago(head.whenMillis), style = inkNum(9.5, Ink.mid))
        }
        Spacer(Modifier.height(7.dp))
        // Always add first, then the drop that paid for it. ESPN returns
        // the pair in either order, which made identical moves read
        // differently from one row to the next.
        group.sortedBy {
            when (it.kind) {
                TxKind.ADD, TxKind.WAIVER_ADD -> 0
                TxKind.TRADE -> 1
                else -> 2
            }
        }.forEach { tx -> TransactionRow(tx, b) }
    }
}

@Composable
private fun TransactionRow(tx: Transaction, b: Brief) {
    val mine = tx.teamId == b.league.myTeamId
    val teamName = b.league.teams.firstOrNull { it.id == tx.teamId }?.name
        ?: "team ${tx.teamId}"
    val who = b.playerNames[tx.playerId]
        ?: if (ActivityLog.isDefense(tx.playerId))
            Enums.proTeam(ActivityLog.defenseProTeamId(tx.playerId)) + " D/ST"
        else "player ${tx.playerId}"

    val owners = b.league.teams
        .flatMap { t -> t.roster.map { it.playerId to t.id } }.toMap()

    // Derived, never copied from the log. A failed rival claim leaves no trace
    // in ESPN's feed, so that consequence is deliberately absent.
    val consequence = when {
        tx.kind == TxKind.TRADE ->
            when (ActivityLog.tradeState(tx, owners)) {
                TradeState.NOT_COMPLETED -> "unaccepted, rosters unchanged"
                TradeState.COMPLETED -> "completed"
                TradeState.UNKNOWN -> ""
            }
        tx.kind == TxKind.DROP -> {
            val pickedUp = b.transactions.firstOrNull {
                it.playerId == tx.playerId && it.whenMillis > tx.whenMillis &&
                    (it.kind == TxKind.ADD || it.kind == TxKind.WAIVER_ADD)
            }
            when {
                pickedUp != null -> {
                    val by = b.league.teams.firstOrNull { it.id == pickedUp.teamId }?.name
                    val mins = (pickedUp.whenMillis - tx.whenMillis) / 60000
                    "picked up ${if (mins < 60) "$mins min" else "${mins / 60}h"} " +
                        "later by ${by ?: "another team"}"
                }
                b.tiers[Tier.ELITE]?.any { it.player.playerId == tx.playerId } == true ->
                    "now clears the median ${b.playerNames[tx.playerId]?.let { "" } ?: ""}" +
                        "starter on the wire"
                else -> ""
            }
        }
        tx.kind == TxKind.WAIVER_ADD -> "won on waivers"
        else -> ""
    }

    val badge = when (tx.kind) {
        TxKind.ADD, TxKind.WAIVER_ADD -> "ADD" to Ink.positive
        TxKind.DROP -> "DROP" to Ink.negative
        TxKind.TRADE -> "TRADE" to Ink.accent
        else -> tx.kind.name.take(6) to Ink.mid
    }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(who, style = inkBody(12.5, Ink.paper))
                    // Who he actually is — a name alone says nothing about
                    // whether the move mattered.
                    val known = b.league.teams.flatMap { t -> t.roster }
                        .firstOrNull { it.playerId == tx.playerId }
                        ?: b.pool.firstOrNull { it.playerId == tx.playerId }
                            ?.let { w ->
                                b.league.teams.flatMap { t -> t.roster }
                                    .firstOrNull { it.playerId == w.playerId }
                            }
                    val pos = known?.position
                        ?: b.pool.firstOrNull { it.playerId == tx.playerId }?.position
                    val proTeam = known?.proTeamId
                        ?: b.pool.firstOrNull { it.playerId == tx.playerId }?.proTeamId
                    if (pos != null && proTeam != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            listOfNotNull(
                                b.depth.label(proTeam, tx.playerId) ?: pos,
                                b.proTeams.abbrev(proTeam)
                            ).joinToString(" "),
                            style = inkLabel(8.5, Ink.mid)
                        )
                    }
                }
                if (consequence.isNotBlank()) {
                    Text(consequence, style = inkBody(10.0, Ink.mid),
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Box(
                Modifier.width(56.dp).clip(RoundedCornerShape(3.dp))
                    .border(1.dp, badge.second, RoundedCornerShape(3.dp))
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) { Text(badge.first, style = inkLabel(8.5, badge.second)) }
        }
    }
}

// ---- small shared pieces --------------------------------------------------

@Composable
fun InkSectionRow(title: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
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

@Composable
private fun OutlineAction(
    label: String,
    color: Color,
    fill: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .then(if (fill) Modifier.background(color) else Modifier)
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = inkLabel(11.0, if (fill) Ink.ground else color),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (active) Ink.accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (active) Ink.accent else Ink.border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(label, style = inkLabel(10.0, if (active) Ink.paper else Ink.mid)) }
}

private fun ago(millis: Long): String {
    val h = (System.currentTimeMillis() - millis) / 3_600_000
    return when {
        h < 1 -> "now"
        h < 24 -> "${h}H"
        else -> "${h / 24}D"
    }
}
