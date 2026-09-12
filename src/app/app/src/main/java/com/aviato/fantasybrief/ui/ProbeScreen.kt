package com.aviato.fantasybrief.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.EspnClient
import com.aviato.fantasybrief.data.ActivityDecoder
import com.aviato.fantasybrief.data.EspnWriteClient
import com.aviato.fantasybrief.data.IdJoinProbe
import com.aviato.fantasybrief.data.NflScoreboardClient
import com.aviato.fantasybrief.data.LeagueParser
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.JsonProbe
import com.aviato.fantasybrief.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aviato.fantasybrief.data.WaiverClock
import com.aviato.fantasybrief.data.TxKind
import com.aviato.fantasybrief.data.ActivityLog

// The header that trips up everyone who tries this API. Not a query param.
private const val WIRE_FILTER =
    """{"players":{"filterStatus":{"value":["FREEAGENT","WAIVERS"]},""" +
        """"limit":50,"sortPercOwned":{"sortAsc":false,"sortPriority":1}}}"""

private const val ACTIVITY_WIDE =
    """{"topics":{"filterType":{"value":["ACTIVITY_TRANSACTIONS"]},""" +
        """"limit":100,"sortMessageDate":{"sortPriority":1,"sortAsc":false}}}"""

private const val ACTIVITY_FILTER =
    """{"topics":{"filterType":{"value":["ACTIVITY_TRANSACTIONS"]},""" +
        """"limit":25,"sortMessageDate":{"sortPriority":1,"sortAsc":false}}}"""

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProbeScreen(
    store: SecretStore,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { EspnClient(store) }

    var leagueId by rememberSaveable { mutableStateOf("1237544639") }
    var season by rememberSaveable { mutableStateOf("2026") }
    var depth by rememberSaveable { mutableIntStateOf(3) }
    var output by rememberSaveable { mutableStateOf("Tap a probe button.") }
    var busy by remember { mutableStateOf(false) }

    fun probe(label: String, filter: String? = null, buildUrl: (Int, Long) -> String) {
        val id = leagueId.trim().toLongOrNull()
        val yr = season.trim().toIntOrNull()
        if (id == null || yr == null) {
            output = "League id and season must be numbers."
            return
        }
        busy = true
        output = "Fetching $label..."
        scope.launch {
            val url = buildUrl(yr, id)
            val result = withContext(Dispatchers.IO) { client.get(url, filter) }
            val outline = withContext(Dispatchers.Default) {
                if (result.code == 200) JsonProbe.outline(result.body, depth) else ""
            }
            output = buildString {
                append("URL: ").append(url).append("\n")
                if (filter != null) append("FILTER: ").append(filter).append("\n")
                append("HTTP ").append(result.code)
                append("   ").append(result.body.length).append(" chars")
                append("   ").append(result.millis).append(" ms")
                append("   depth ").append(depth).append("\n\n")
                if (result.error != null) append("ERROR: ").append(result.error).append("\n\n")
                append(
                    when (result.code) {
                        200 -> outline
                        401 -> "401 — cookies stale. Sign out and paste fresh ones."
                        404 -> "404 — no league with that id for that season."
                        400 -> "400 — bad filter, or a limit with no sort.\n\n" +
                            result.body.take(600)
                        -1 -> "No response. Network down or host unreachable."
                        else -> result.body.take(1200)
                    }
                )
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ESPN probe", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = leagueId, onValueChange = { leagueId = it },
                label = { Text("League id") }, singleLine = true,
                modifier = Modifier.weight(2f)
            )
            OutlinedTextField(
                value = season, onValueChange = { season = it },
                label = { Text("Season") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        // Depth matters: the default of 3 truncates lineupSlotCounts, which is
        // exactly the map we need to read roster rules off.
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Depth", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 10.dp))
            listOf(3, 5, 8).forEach { d ->
                FilterChip(
                    selected = depth == d,
                    onClick = { depth = d },
                    label = { Text(d.toString()) }
                )
            }
        }

        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    probe("settings") { yr, id -> client.leagueUrl(yr, id, "mSettings") }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Settings", fontSize = 12.sp) }
            Button(
                onClick = {
                    probe("roster") { yr, id ->
                        client.leagueUrl(yr, id, "mTeam", "mRoster")
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Roster", fontSize = 12.sp) }
            Button(
                onClick = {
                    probe("proTeams") { yr, _ ->
                        client.seasonUrl(yr, "proTeamSchedules_wl")
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Pro", fontSize = 12.sp) }

            Button(
                onClick = {
                    probe("wire", WIRE_FILTER) { yr, id ->
                        client.leagueUrl(yr, id, "kona_player_info")
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Wire", fontSize = 12.sp) }
            Button(
                onClick = {
                    // Different path entirely. Sending this filter to the
                    // league endpoint returns 400.
                    probe("activity", ACTIVITY_FILTER) { yr, id ->
                        client.leagueUrl(yr, id) +
                            "/communication/?view=kona_league_communication"
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Activity", fontSize = 12.sp) }
            Button(
                onClick = {
                    probe("matchups") { yr, id -> client.leagueUrl(yr, id, "mMatchup") }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Matchup", fontSize = 12.sp) }

            Button(
                onClick = {
                    // Only PRIVATE view in the dump — returns MY claims, not rivals'.
                    probe("pending") { yr, id ->
                        client.leagueUrl(yr, id, "mPendingTransactions")
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Pending", fontSize = 12.sp) }
            Button(
                onClick = {
                    // transactionCounter reads 0 for adds. Find where they land.
                    probe("txCounter") { yr, id -> client.leagueUrl(yr, id, "mTeam") }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("TxCount", fontSize = 12.sp) }
            Button(
                onClick = {
                    val id = leagueId.trim().toLongOrNull()
                    val yr = season.trim().toIntOrNull()
                    if (id == null || yr == null) return@Button
                    busy = true; output = "Decoding activity..."
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            // Names make the decode checkable; without them
                            // it is a wall of integers.
                            val lg = client.get(
                                client.leagueUrl(yr, id, "mTeam", "mRoster", "mSettings")
                            )
                            val league = if (lg.ok) runCatching {
                                LeagueParser.parse(lg.body, id, yr, store.swid)
                            }.getOrNull() else null

                            val act = client.get(
                                client.leagueUrl(yr, id) +
                                    "/communication/?view=kona_league_communication",
                                ACTIVITY_WIDE
                            )
                            if (!act.ok) "HTTP ${act.code}"
                            else runCatching { ActivityDecoder.decode(act.body, league) }
                                .getOrElse { "Decode failed: ${it.message}" }
                        }
                        output = text
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Decode", fontSize = 12.sp) }

            Button(
                onClick = {
                    val id = leagueId.trim().toLongOrNull()
                    val yr = season.trim().toIntOrNull()
                    if (id == null || yr == null) return@Button
                    busy = true; output = "Probing public ESPN APIs..."
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val lg = client.get(
                                client.leagueUrl(yr, id, "mTeam", "mRoster", "mSettings")
                            )
                            if (!lg.ok) "League fetch failed: HTTP ${lg.code}"
                            else runCatching {
                                IdJoinProbe.run(
                                    LeagueParser.parse(lg.body, id, yr, store.swid), yr
                                )
                            }.getOrElse { "Probe failed: ${it.message}" }
                        }
                        output = text
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("ID join", fontSize = 12.sp) }

            Button(
                onClick = {
                    val id = leagueId.trim().toLongOrNull()
                    val yr = season.trim().toIntOrNull()
                    if (id == null || yr == null) return@Button
                    busy = true; output = "Building write payload (NOT sending)..."
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val lg = client.get(
                                client.leagueUrl(yr, id, "mTeam", "mRoster", "mSettings")
                            )
                            if (!lg.ok) return@withContext "League fetch failed"
                            val league = runCatching {
                                LeagueParser.parse(lg.body, id, yr, store.swid)
                            }.getOrNull() ?: return@withContext "Parse failed"
                            val team = league.myTeam
                                ?: return@withContext "No team detected"

                            // A no-op move: bench player back to his own slot.
                            // Shows the payload shape without proposing a change.
                            val sample = team.roster.firstOrNull { !it.isStarter }
                                ?: return@withContext "No bench player"

                            val res = EspnWriteClient(store).setLineup(
                                yr, id, team.id,
                                league.settings.scoringPeriodId,
                                listOf(Triple(sample.playerId,
                                    sample.lineupSlotId, sample.lineupSlotId)),
                                dryRun = true
                            )
                            buildString {
                                append("DRY RUN — nothing was sent.\n\n")
                                append("POST ").append(EspnWriteClient.WRITE_HOST)
                                append("/apis/v3/games/ffl/seasons/").append(yr)
                                append("/segments/0/leagues/").append(id)
                                append("/transactions/\n\n")
                                append("Payload:\n").append(res.sentPayload)
                                append("\n\nSample player: ").append(sample.name)
                                append(" (slot ").append(sample.lineupSlotId).append(")")
                            }
                        }
                        output = text
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Write payload", fontSize = 12.sp) }

            Button(
                onClick = {
                    val id = leagueId.trim().toLongOrNull()
                    val yr = season.trim().toIntOrNull()
                    if (id == null || yr == null) return@Button
                    busy = true; output = "Sending NO-OP lineup transaction..."
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val lg = client.get(
                                client.leagueUrl(yr, id, "mTeam", "mRoster", "mSettings")
                            )
                            if (!lg.ok) return@withContext "League fetch failed"
                            val league = runCatching {
                                LeagueParser.parse(lg.body, id, yr, store.swid)
                            }.getOrNull() ?: return@withContext "Parse failed"
                            val team = league.myTeam
                                ?: return@withContext "No team detected"
                            val sample = team.roster.firstOrNull { !it.isStarter }
                                ?: return@withContext "No bench player"

                            // from == to. Even a full success changes nothing.
                            val res = EspnWriteClient(store).setLineup(
                                yr, id, team.id,
                                league.settings.scoringPeriodId,
                                listOf(Triple(sample.playerId,
                                    sample.lineupSlotId, sample.lineupSlotId)),
                                dryRun = false
                            )
                            buildString {
                                append("NO-OP SENT — ").append(sample.name)
                                append(" slot ").append(sample.lineupSlotId)
                                append(" to ").append(sample.lineupSlotId).append("\n\n")
                                append("HTTP ").append(res.code).append("\n")
                                res.error?.let { e ->
                                    append("ERROR: ").append(e).append("\n")
                                }
                                append("\nResponse:\n")
                                append(res.body.take(1500).ifBlank { "(empty)" })
                                append("\n\nSent:\n").append(res.sentPayload)
                            }
                        }
                        output = text
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Send no-op", fontSize = 12.sp) }

            Button(
                onClick = {
                    val yr = season.trim().toIntOrNull() ?: return@Button
                    busy = true; output = "Fetching NFL scoreboard..."
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val c = NflScoreboardClient()
                            val res = c.rawFor(1, yr)
                            if (!res.ok) return@withContext "HTTP ${res.code} ${res.error ?: ""}"
                            val parsed = runCatching { c.parse(res.body) }
                                .getOrElse { return@withContext "Parse failed: ${it.message}" }
                            buildString {
                                append("HTTP ").append(res.code).append(", ")
                                append(res.body.length).append(" chars\n")
                                append("Parsed ").append(parsed.size)
                                append(" teams (expect 32 in a full week)\n\n")
                                parsed.values.take(6).forEach { g ->
                                    append(g.line(Enums.proTeam(g.proTeamId)))
                                    append("  \u00B7 ").append(g.statusText)
                                    append("  [").append(g.state).append("]\n")
                                }
                                append("\nRaw outline:\n")
                                append(JsonProbe.outline(res.body, 4).take(2500))
                            }
                        }
                        output = text
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Scoreboard", fontSize = 12.sp) }

            Button(
                onClick = {
                    // If ESPN fills rosterForMatchupPeriod after a week ends,
                    // past lineups are exact AND retroactive, and the local
                    // archive becomes a fallback rather than the only source.
                    probe("pastRoster") { yr, id ->
                        client.leagueUrl(yr, id, "mMatchup") + "&scoringPeriodId=1"
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Past roster", fontSize = 12.sp) }

            Button(
                onClick = {
                    val id = leagueId.trim().toLongOrNull()
                    val yr = season.trim().toIntOrNull()
                    if (id == null || yr == null) return@Button
                    busy = true; output = "Asking for a future week..."
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val out = StringBuilder()
                            // Does mRoster honour scoringPeriodId the way
                            // mMatchup does? If it returns week-N stats rows,
                            // forward projections are one parameter away.
                            listOf(1, 5, 9).forEach { wk ->
                                val url = client.leagueUrl(yr, id, "mRoster", "mTeam") +
                                    "&scoringPeriodId=" + wk
                                val res = client.get(url)
                                out.append("scoringPeriodId=").append(wk)
                                    .append("  HTTP ").append(res.code)
                                    .append("  ").append(res.body.length)
                                    .append(" chars\n")
                                if (!res.ok) { out.append("\n"); return@forEach }

                                // Report which stats rows came back for the
                                // first rostered player.
                                runCatching {
                                    val root = org.json.JSONObject(res.body)
                                    val entry = root.optJSONArray("teams")
                                        ?.optJSONObject(0)
                                        ?.optJSONObject("roster")
                                        ?.optJSONArray("entries")?.optJSONObject(0)
                                    val player = entry?.optJSONObject("playerPoolEntry")
                                        ?.optJSONObject("player")
                                    out.append("  ")
                                        .append(player?.optString("fullName") ?: "?")
                                        .append("\n")
                                    val stats = player?.optJSONArray("stats")
                                    for (i in 0 until (stats?.length() ?: 0)) {
                                        val r = stats?.optJSONObject(i) ?: continue
                                        out.append("    src=")
                                            .append(r.optInt("statSourceId", -1))
                                            .append(" split=")
                                            .append(r.optInt("statSplitTypeId", -1))
                                            .append(" period=")
                                            .append(r.optInt("scoringPeriodId", -1))
                                            .append(" total=")
                                            .append(
                                                String.format(
                                                    java.util.Locale.US, "%.1f",
                                                    r.optDouble("appliedTotal", 0.0)
                                                )
                                            ).append("\n")
                                    }
                                }.onFailure { out.append("  parse failed\n") }
                                out.append("\n")
                            }
                            out.toString()
                        }
                        output = text
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Future proj", fontSize = 12.sp) }

        }

            Button(
                onClick = {
                    val id = leagueId.trim().toLongOrNull()
                    val yr = season.trim().toIntOrNull()
                    if (id == null || yr == null) return@Button
                    busy = true; output = "Building add payloads (NOT sending)..."
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val lg = client.get(
                                client.leagueUrl(yr, id, "mTeam", "mRoster", "mSettings")
                            )
                            if (!lg.ok) return@withContext "League fetch failed"
                            val league = runCatching {
                                LeagueParser.parse(lg.body, id, yr, store.swid)
                            }.getOrNull() ?: return@withContext "Parse failed"
                            val team = league.myTeam
                                ?: return@withContext "No team detected"
                            val drop = team.roster.firstOrNull { !it.isStarter }
                                ?: return@withContext "No bench player"

                            val w = EspnWriteClient(store)
                            val fa = w.addPlayer(
                                yr, id, team.id, league.settings.scoringPeriodId,
                                addPlayerId = 99999, dropPlayerId = drop.playerId,
                                isWaiverClaim = false, dryRun = true
                            )
                            val claim = w.addPlayer(
                                yr, id, team.id, league.settings.scoringPeriodId,
                                addPlayerId = 99999, dropPlayerId = drop.playerId,
                                isWaiverClaim = true, dryRun = true
                            )
                            buildString {
                                append("DRY RUN — nothing sent.\n\n")
                                append("Would drop: ").append(drop.name).append("\n\n")
                                append("FREE AGENT ADD:\n").append(fa.sentPayload)
                                append("\n\nWAIVER CLAIM:\n").append(claim.sentPayload)
                                append("\n\nWaiver rules here: ")
                                append(league.settings.waiverSummary)
                            }
                        }
                        output = text
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Add payload", fontSize = 12.sp) }

            Button(
                onClick = {
                    val id = leagueId.trim().toLongOrNull()
                    val yr = season.trim().toIntOrNull()
                    if (id == null || yr == null) return@Button
                    busy = true; output = "Sending an INVALID add..."
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val lg = client.get(
                                client.leagueUrl(yr, id, "mTeam", "mRoster", "mSettings")
                            )
                            if (!lg.ok) return@withContext "League fetch failed"
                            val league = runCatching {
                                LeagueParser.parse(lg.body, id, yr, store.swid)
                            }.getOrNull() ?: return@withContext "Parse failed"
                            val team = league.myTeam
                                ?: return@withContext "No team detected"
                            val drop = team.roster.firstOrNull { !it.isStarter }
                                ?: return@withContext "No bench player"

                            // Player 99999 does not exist, so this CANNOT
                            // succeed. What we want is the error: a shape
                            // complaint means the payload is wrong, a
                            // "no such player" means the shape was understood.
                            val res = EspnWriteClient(store).addPlayer(
                                yr, id, team.id, league.settings.scoringPeriodId,
                                addPlayerId = 99999, dropPlayerId = drop.playerId,
                                isWaiverClaim = false, dryRun = false
                            )
                            buildString {
                                append("Sent an add for player 99999 (does not exist)\n")
                                append("Would have dropped: ").append(drop.name)
                                append("\n\nHTTP ").append(res.code).append("\n")
                                res.error?.let { e -> append("ERROR: ").append(e).append("\n") }
                                append("\nResponse:\n")
                                append(res.body.take(1500).ifBlank { "(empty)" })
                            }
                        }
                        output = text
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Send bad add", fontSize = 12.sp) }

            Button(
                onClick = {
                    val id = leagueId.trim().toLongOrNull()
                    val yr = season.trim().toIntOrNull()
                    if (id == null || yr == null) return@Button
                    busy = true; output = "Trying claim shapes with a fake player..."
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val lg = client.get(
                                client.leagueUrl(yr, id, "mTeam", "mRoster", "mSettings")
                            )
                            if (!lg.ok) return@withContext "League fetch failed"
                            val league = runCatching {
                                LeagueParser.parse(lg.body, id, yr, store.swid)
                            }.getOrNull() ?: return@withContext "Parse failed"
                            val team = league.myTeam
                                ?: return@withContext "No team detected"
                            val drop = team.roster.firstOrNull { !it.isStarter }
                                ?: return@withContext "No bench player"

                            // Player 99999 cannot be claimed, so PLAYER_NOT_EXISTS
                            // is the SUCCESS signal — it means every field
                            // validated and only the id was wrong.
                            val combos = listOf(
                                Triple("WAIVER", "EXECUTE", "WAIVER"),
                                Triple("WAIVER", "QUEUE", "WAIVER"),
                                Triple("WAIVER", "EXECUTE", "ADD"),
                                Triple("WAIVER", "QUEUE", "ADD"),
                                Triple("FREEAGENT", "QUEUE", "WAIVER"),
                                Triple("WAIVER_REQUEST", "EXECUTE", "ADD")
                            )
                            val w = EspnWriteClient(store)
                            buildString {
                                append("Fake player 99999. PLAYER_NOT_EXISTS = shape OK\n\n")
                                combos.forEach { (env, exec, item) ->
                                    val res = w.addPlayer(
                                        yr, id, team.id,
                                        league.settings.scoringPeriodId,
                                        addPlayerId = 99999,
                                        dropPlayerId = drop.playerId,
                                        isWaiverClaim = true, dryRun = false,
                                        envelopeType = env, executionType = exec,
                                        itemType = item
                                    )
                                    append(env).append(" / ").append(exec)
                                        .append(" / ").append(item)
                                        .append("  ->  HTTP ").append(res.code).append("\n   ")
                                    append(res.body.take(220).ifBlank { res.error ?: "" })
                                    append("\n\n")
                                }
                            }
                        }
                        output = text
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Claim matrix", fontSize = 12.sp) }

            Button(
                onClick = {
                    val id = leagueId.trim().toLongOrNull()
                    val yr = season.trim().toIntOrNull()
                    if (id == null || yr == null) return@Button
                    busy = true; output = "Computing..."
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val lg = client.get(client.leagueUrl(yr, id, "mSettings"))
                            if (!lg.ok) return@withContext "settings fetch failed"
                            val league = runCatching {
                                LeagueParser.parse(lg.body, id, yr, store.swid)
                            }.getOrNull() ?: return@withContext "parse failed"
                            val st = league.settings

                            val act = client.get(
                                client.leagueUrl(yr, id) +
                                    "/communication/?view=kona_league_communication",
                                ActivityLog.FILTER
                            )
                            val lastDrop = if (act.ok)
                                runCatching { ActivityLog.parse(act.body) }
                                    .getOrDefault(emptyList())
                                    .firstOrNull { it.kind == TxKind.DROP }
                            else null

                            buildString {
                                append("device tz: ")
                                append(java.util.TimeZone.getDefault().id).append("\n\n")
                                if (lastDrop == null) {
                                    append("no drop in the activity log to test with")
                                } else {
                                    append(
                                        WaiverClock.debug(
                                            lastDrop.whenMillis,
                                            st.waiverProcessDays,
                                            st.waiverProcessHour,
                                            st.waiverHours
                                        )
                                    )
                                }
                            }
                        }
                        output = text
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.width(108.dp)
            ) { Text("Waiver clock", fontSize = 12.sp) }

        HorizontalDivider(Modifier.padding(top = 8.dp))

        Text(
            output,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            modifier = Modifier.weight(1f)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        )

        Row(
            Modifier.fillMaxWidth().padding(bottom = bottomInset + 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("probe", output))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Copy", fontSize = 13.sp) }
            // Share survives sizes the clipboard mangles on some keyboards.
            OutlinedButton(
                onClick = {
                    val send = android.content.Intent(
                        android.content.Intent.ACTION_SEND
                    ).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, output)
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(send, "Send probe")
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("Share", fontSize = 13.sp) }
        }
    }
}
