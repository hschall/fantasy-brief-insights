package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DepthMove(
    val playerId: Int,
    val proTeamId: Int,
    val slot: String,
    val fromRank: Int,
    val toRank: Int,
    val observedAtMillis: Long
) {
    val promoted: Boolean get() = toRank < fromRank
}

/**
 * Remembers depth charts so a change can be detected.
 *
 * DepthChartLoader caches for a day and overwrites, so there was never a prior
 * state to compare against — "RB2 to RB1" was uncomputable. This keeps dated
 * snapshots and diffs the newest against the oldest inside a window.
 *
 * It records from the first run, but a MOVE needs two observations. Until the
 * second one exists the feed is legitimately empty, and the tab says so
 * rather than inventing anything.
 */
class DepthHistory(context: Context) {

    private val file = File(context.filesDir, "depth_history.json")

    /** One dated observation: proTeamId -> playerId -> "slot:rank". */
    private fun load(): List<Pair<Long, Map<Int, Map<Int, String>>>> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val teams = o.optJSONObject("t") ?: continue
                    val parsed = mutableMapOf<Int, Map<Int, String>>()
                    teams.keys().forEach { tk ->
                        val teamId = tk.toIntOrNull() ?: return@forEach
                        val players = teams.optJSONObject(tk) ?: return@forEach
                        val inner = mutableMapOf<Int, String>()
                        players.keys().forEach { pk ->
                            pk.toIntOrNull()?.let { inner[it] = players.optString(pk) }
                        }
                        parsed[teamId] = inner
                    }
                    add(o.optLong("d") to parsed)
                }
            }.sortedBy { it.first }
        }.getOrDefault(emptyList())
    }

    fun record(charts: DepthCharts, proTeamIds: Set<Int>) {
        if (charts.teamsLoaded == 0) return
        val now = System.currentTimeMillis()
        val runs = load().toMutableList()
        // One observation per 6h is plenty for something that moves weekly,
        // and keeps the file small.
        runs.lastOrNull()?.let { if (now - it.first < THROTTLE) return }

        val snapshot = mutableMapOf<Int, Map<Int, String>>()
        proTeamIds.forEach { team ->
            val entries = charts.entriesFor(team)
            if (entries.isEmpty()) return@forEach
            snapshot[team] = entries.associate { it.athleteId to "${it.slot}:${it.rank}" }
        }
        if (snapshot.isEmpty()) return

        runs.add(now to snapshot)
        val cutoff = now - RETENTION
        save(runs.filter { it.first >= cutoff })
    }

    /** Promotions and demotions inside the window. Empty until two runs exist. */
    fun moves(windowHours: Long): List<DepthMove> {
        val runs = load()
        if (runs.size < 2) return emptyList()
        val target = System.currentTimeMillis() - windowHours * 3_600_000
        val before = runs.firstOrNull { it.first >= target } ?: runs.first()
        val now = runs.last()
        if (before.first == now.first) return emptyList()

        val out = mutableListOf<DepthMove>()
        now.second.forEach { (team, players) ->
            val prev = before.second[team] ?: return@forEach
            players.forEach { (playerId, value) ->
                val old = prev[playerId] ?: return@forEach
                if (old == value) return@forEach
                val (oldSlot, oldRank) = old.split(":")
                val (newSlot, newRank) = value.split(":")
                // A slot change is a position reclassification, not a move up
                // or down the same chart.
                if (oldSlot != newSlot) return@forEach
                out.add(
                    DepthMove(
                        playerId, team, newSlot,
                        oldRank.toIntOrNull() ?: return@forEach,
                        newRank.toIntOrNull() ?: return@forEach,
                        now.first
                    )
                )
            }
        }
        return out.sortedWith(
            compareByDescending<DepthMove> { it.promoted }
                .thenBy { it.toRank }
        )
    }

    fun observationCount() = load().size

    private fun save(runs: List<Pair<Long, Map<Int, Map<Int, String>>>>) {
        val arr = JSONArray()
        runs.forEach { (date, teams) ->
            val t = JSONObject()
            teams.forEach { (teamId, players) ->
                val p = JSONObject()
                players.forEach { (id, v) -> p.put(id.toString(), v) }
                t.put(teamId.toString(), p)
            }
            arr.put(JSONObject().apply { put("d", date); put("t", t) })
        }
        file.writeText(arr.toString())
    }

    private companion object {
        const val THROTTLE = 6L * 60 * 60 * 1000
        const val RETENTION = 14L * 24 * 60 * 60 * 1000
    }
}
