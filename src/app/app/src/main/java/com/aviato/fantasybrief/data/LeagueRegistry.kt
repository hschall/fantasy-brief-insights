package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LeagueRef(
    val leagueId: Long,
    val season: Int,
    val name: String,
    val teamName: String?,
    val teamId: Int?
) {
    val key: String get() = "${leagueId}_$season"
}

/**
 * The leagues this user follows, and which one is active.
 *
 * Every other store (observations, events, alert mutes) is already keyed by
 * leagueId and season, so switching leagues cannot corrupt another league's
 * history. That was designed in at Stage 6 so this stage stayed additive.
 */
class LeagueRegistry(context: Context) {

    private val prefs = context.getSharedPreferences("leagues", Context.MODE_PRIVATE)

    fun all(): List<LeagueRef> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        LeagueRef(
                            leagueId = o.optLong("id"),
                            season = o.optInt("season"),
                            name = o.optString("name", "League"),
                            teamName = o.optString("team", "").ifBlank { null },
                            teamId = if (o.isNull("teamId")) null else o.optInt("teamId")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(ref: LeagueRef) {
        save(all().filterNot { it.key == ref.key } + ref)
        if (activeKey() == null) setActive(ref)
    }

    fun remove(ref: LeagueRef) {
        save(all().filterNot { it.key == ref.key })
        if (activeKey() == ref.key) prefs.edit().remove(KEY_ACTIVE).apply()
    }

    /** Refreshes cached display names after a successful load. */
    fun rename(leagueId: Long, season: Int, name: String, teamName: String?, teamId: Int?) {
        save(all().map {
            if (it.leagueId == leagueId && it.season == season)
                it.copy(name = name, teamName = teamName, teamId = teamId)
            else it
        })
    }

    fun setActive(ref: LeagueRef) = prefs.edit().putString(KEY_ACTIVE, ref.key).apply()

    fun active(): LeagueRef? {
        val key = activeKey() ?: return all().firstOrNull()
        return all().firstOrNull { it.key == key } ?: all().firstOrNull()
    }

    private fun activeKey() = prefs.getString(KEY_ACTIVE, null)

    private fun save(list: List<LeagueRef>) {
        val arr = JSONArray()
        list.forEach { ref ->
            arr.put(
                JSONObject().apply {
                    put("id", ref.leagueId)
                    put("season", ref.season)
                    put("name", ref.name)
                    ref.teamName?.let { put("team", it) }
                    ref.teamId?.let { put("teamId", it) }
                }
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private companion object {
        const val KEY_LIST = "leagues"
        const val KEY_ACTIVE = "active"
    }
}
