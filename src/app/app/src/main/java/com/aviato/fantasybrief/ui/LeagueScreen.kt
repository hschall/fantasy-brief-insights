package com.aviato.fantasybrief.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.TeamShape
import com.aviato.fantasybrief.data.TeamShapes

@Composable
fun LeagueScreen(
    brief: Brief?,
    loading: Boolean,
    bottomInset: Dp,
    onPlayer: (PlayerFocus) -> Unit
) {
    var expanded by remember { mutableStateOf<Int?>(null) }

    if (loading || brief == null) {
        Column(
            Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) { CircularProgressIndicator(color = Fb.Teal) }
        return
    }

    val week = brief.league.settings.scoringPeriodId
    val shapes = brief.shapes

    Column(Modifier.fillMaxSize()) {
        Masthead(
            title = "League",
            trailing = "${brief.league.settings.size} teams",
            subtitle = "Ranked by total projected starting lineup. Tap a team to " +
                "open its roster."
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 24.dp)
        ) {
            items(shapes) { shape ->
                TeamCard(
                    shape = shape,
                    brief = brief,
                    week = week,
                    expanded = expanded == shape.team.id,
                    onToggle = {
                        expanded = if (expanded == shape.team.id) null else shape.team.id
                    },
                    onPlayer = onPlayer
                )
            }
        }
    }
}

@Composable
private fun TeamCard(
    shape: TeamShape,
    brief: Brief,
    week: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPlayer: (PlayerFocus) -> Unit
) {
    val team = shape.team
    val mine = team.id == brief.league.myTeamId
    val pro = brief.proTeams

    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(if (mine) Fb.Teal.copy(alpha = 0.07f) else Fb.Card)
            .clickable { onToggle() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        team.name,
                        color = if (mine) Fb.Teal else Fb.Ink,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                    if (mine) {
                        Spacer(Modifier.width(8.dp)); TagPill("you", Fb.Teal)
                    }
                }
                MetaRow(
                    team.record to Fb.Muted,
                    "proj rank ${team.projectedRank}" to Fb.Faint,
                    "waiver ${team.waiverRank}" to Fb.Faint
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Stat(fmt1(shape.startingTotal), size = 17)
                Text("starting", color = Fb.Faint, fontSize = 9.sp)
            }
        }

        // Surplus and holes are the whole point of looking at a rival roster.
        if (shape.surplus.isNotEmpty() || shape.holes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            shape.surplus.forEach {
                Row(Modifier.padding(vertical = 1.dp)) {
                    Text("has  ", color = Fb.Green, fontSize = 10.sp)
                    Text(it, color = Fb.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            shape.holes.forEach {
                Row(Modifier.padding(vertical = 1.dp)) {
                    Text("needs  ", color = Fb.Red, fontSize = 10.sp)
                    Text(it, color = Fb.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }

        shape.worstByeWeek?.let { (w, n) ->
            Spacer(Modifier.height(6.dp))
            Text("$n players out in week $w", color = Fb.Amber, fontSize = 11.sp)
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            team.roster.forEach { p ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onPlayer(p.focus(team.id)) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        p.slot, color = Fb.Faint, fontSize = 9.sp,
                        modifier = Modifier.width(32.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 13.sp,
                            color = tierTextColor(
                                brief.tierNameOf(p.position, p.projection, p.playerId)))
                        MetaRow(
                            p.position to Fb.Faint,
                            pro.abbrev(p.proTeamId) to Fb.Faint,
                            (pro.byeWeek(p.proTeamId)?.let { "bye $it" } ?: "") to Fb.Faint,
                            (if (p.healthy) "" else p.injuryTag) to Fb.Red
                        )
                    }
                    ProjectionScale(
                        p.projection, brief.replacement.level(p.position), barWidth = 56.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    StatCell(p.projection?.let { fmt1(it) } ?: "—")
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text("Tap for roster", color = Fb.Faint, fontSize = 10.sp)
        }
    }
}
