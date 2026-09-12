package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Append-only transaction history, per league.
 *
 * Transactions come from ESPN's activity feed, which is authoritative and
 * backfills to the draft — never from a roster diff. Because every event
 * carries a stable id, re-reading the feed is idempotent: refreshing twenty
 * times adds nothing. That is what makes refresh free (bug report §14).
 */
class EventStore(context: Context) {

    private val dir = context.filesDir

    private fun eventsFile(leagueId: Long, season: Int) =
        File(dir, "events_${leagueId}_$season.json")

    private fun namesFile(leagueId: Long, season: Int) =
        File(dir, "names_${leagueId}_$season.json")

    fun load(leagueId: Long, season: Int): List<Transaction> {
        val f = eventsFile(leagueId, season)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Transaction(
                            id = o.optString("id"),
                            kind = runCatching { TxKind.valueOf(o.optString("k")) }
                                .getOrDefault(TxKind.UNKNOWN),
                            whenMillis = o.optLong("w"),
                            teamId = o.optInt("t"),
                            counterpartyTeamId = if (o.isNull("c")) null else o.optInt("c"),
                            playerId = o.optInt("p"),
                            fromSlot = if (o.isNull("f")) null else o.optInt("f"),
                            toSlot = if (o.isNull("s")) null else o.optInt("s")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Merges by event id. Returns only what was genuinely new. */
    fun append(leagueId: Long, season: Int, incoming: List<Transaction>): List<Transaction> {
        val existing = load(leagueId, season)
        val known = existing.map { it.id }.toSet()
        val fresh = incoming.filter { it.id !in known }
        if (fresh.isEmpty()) return emptyList()

        val all = (existing + fresh).sortedByDescending { it.whenMillis }
        val arr = JSONArray()
        all.forEach { tx ->
            arr.put(
                JSONObject().apply {
                    put("id", tx.id); put("k", tx.kind.name); put("w", tx.whenMillis)
                    put("t", tx.teamId); put("p", tx.playerId)
                    tx.counterpartyTeamId?.let { put("c", it) }
                    tx.fromSlot?.let { put("f", it) }
                    tx.toSlot?.let { put("s", it) }
                }
            )
        }
        eventsFile(leagueId, season).writeText(arr.toString())
        return fresh
    }

    // ---- player name cache ------------------------------------------------
    // Dropped players vanish from every roster, so their names must be
    // remembered or history degrades into integers over time.

    fun loadNames(leagueId: Long, season: Int): Map<Int, String> {
        val f = namesFile(leagueId, season)
        if (!f.exists()) return emptyMap()
        return try {
            val o = JSONObject(f.readText())
            buildMap { o.keys().forEach { k -> k.toIntOrNull()?.let { put(it, o.getString(k)) } } }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun rememberNames(leagueId: Long, season: Int, names: Map<Int, String>) {
        if (names.isEmpty()) return
        val merged = loadNames(leagueId, season).toMutableMap()
        var changed = false
        names.forEach { (id, name) ->
            if (name.isNotBlank() && merged[id] != name) { merged[id] = name; changed = true }
        }
        if (!changed) return
        val o = JSONObject()
        merged.forEach { (id, name) -> o.put(id.toString(), name) }
        namesFile(leagueId, season).writeText(o.toString())
    }
}
