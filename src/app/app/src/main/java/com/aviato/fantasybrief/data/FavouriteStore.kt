package com.aviato.fantasybrief.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray

/**
 * Starred players. One list, app-wide.
 *
 * The star in Candidates and the star on the wire board read and write this
 * same collection — two stars that meant different things would be two
 * concepts wearing one glyph, and alert scoping ("starred players only")
 * would then have to pick a side.
 *
 * Scoped per league: the same player can be a watch in Chem and irrelevant in
 * IPADE, and the leagues have different scoring. Deliberately NOT expired,
 * unlike AlertStore — a star is a standing instruction and survives the week
 * roll-over and the waiver reset. It goes away when you take it away.
 */
class FavouriteStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("favourites", Context.MODE_PRIVATE)

    /**
     * Backed by snapshot state so a star redraws immediately.
     *
     * SharedPreferences is the durable copy; this is what Compose observes.
     * Reading prefs directly inside a composable would persist fine and
     * never recompose, which looks exactly like a broken tap.
     */
    private val cache = mutableStateMapOf<Long, Set<Int>>()

    fun ids(leagueId: Long): Set<Int> = cache.getOrPut(leagueId) { read(leagueId) }

    fun isStarred(leagueId: Long, playerId: Int) = playerId in ids(leagueId)

    fun toggle(leagueId: Long, playerId: Int): Boolean {
        val next = ids(leagueId).toMutableSet()
        val nowStarred = if (playerId in next) { next.remove(playerId); false }
                         else { next.add(playerId); true }
        cache[leagueId] = next
        write(leagueId, next)
        return nowStarred
    }

    fun clear(leagueId: Long) {
        cache[leagueId] = emptySet()
        prefs.edit().remove(key(leagueId)).apply()
    }

    private fun key(leagueId: Long) = "league_$leagueId"

    private fun read(leagueId: Long): Set<Int> {
        val raw = prefs.getString(key(leagueId), null) ?: return emptySet()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getInt(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun write(leagueId: Long, ids: Set<Int>) {
        val a = JSONArray()
        ids.forEach { a.put(it) }
        prefs.edit().putString(key(leagueId), a.toString()).apply()
    }
}
