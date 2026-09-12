package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class NewsItem(
    val id: String,
    val headline: String,
    val description: String,
    val publishedMillis: Long,
    val url: String?,
    /** Athlete ids tagged on the article. Same namespace as fantasy playerId. */
    val playerIds: List<Int>
) {
    val hoursAgo: Long
        get() = (System.currentTimeMillis() - publishedMillis) / 3_600_000
}

/**
 * ESPN's NFL news feed, joined to players by athlete id.
 *
 * VERIFIED 2026-09-09: articles carry a categories[] array containing
 * {type: "athlete", athleteId} entries, and those ids are the SAME namespace
 * as the fantasy playerId. So one unauthenticated request maps headlines onto
 * every player in the league — no scraping, no article parsing.
 *
 * The feed is LIVE, not an archive: 50 articles covered about sixteen hours.
 * Polling once and keeping what we have seen is the only way to build any
 * history, which is the same reason the observation store exists.
 */
class NewsStore(context: Context) {

    private val file = File(context.filesDir, "news.json")

    fun all(): List<NewsItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val ids = o.optJSONArray("p") ?: JSONArray()
                    add(
                        NewsItem(
                            id = o.optString("id"),
                            headline = o.optString("h"),
                            description = o.optString("d", ""),
                            publishedMillis = o.optLong("t"),
                            url = o.optString("u", "").ifBlank { null },
                            playerIds = (0 until ids.length()).map { ids.optInt(it) }
                        )
                    )
                }
            }.sortedByDescending { it.publishedMillis }
        }.getOrDefault(emptyList())
    }

    /** Merges new articles in, keeping three days. */
    fun record(fresh: List<NewsItem>) {
        if (fresh.isEmpty()) return
        val known = all().associateBy { it.id }.toMutableMap()
        fresh.forEach { known[it.id] = it }
        val cutoff = System.currentTimeMillis() - RETENTION
        val kept = known.values
            .filter { it.publishedMillis >= cutoff }
            .sortedByDescending { it.publishedMillis }
            .take(300)

        val arr = JSONArray()
        kept.forEach { n ->
            val ids = JSONArray()
            n.playerIds.forEach { ids.put(it) }
            arr.put(
                JSONObject().apply {
                    put("id", n.id); put("h", n.headline); put("d", n.description)
                    put("t", n.publishedMillis)
                    n.url?.let { put("u", it) }
                    put("p", ids)
                }
            )
        }
        file.writeText(arr.toString())
    }

    /** playerId -> his most recent article. */
    fun byPlayer(): Map<Int, NewsItem> {
        val out = mutableMapOf<Int, NewsItem>()
        all().forEach { item ->
            item.playerIds.forEach { id ->
                val existing = out[id]
                if (existing == null || item.publishedMillis > existing.publishedMillis) {
                    out[id] = item
                }
            }
        }
        return out
    }

    private companion object {
        const val RETENTION = 3L * 24 * 60 * 60 * 1000
    }
}

class NewsClient {

    private val http = PublicEspnClient()

    fun fetch(limit: Int = 50): List<NewsItem> {
        val res = http.get(
            "${PublicEspnClient.SITE}/apis/site/v2/sports/football/nfl/news?limit=$limit"
        )
        if (!res.ok) return emptyList()
        return runCatching { parse(res.body) }.getOrDefault(emptyList())
    }

    fun parse(raw: String): List<NewsItem> {
        val arts = JSONObject(raw).optJSONArray("articles") ?: return emptyList()
        return buildList {
            for (i in 0 until arts.length()) {
                val a = arts.optJSONObject(i) ?: continue
                val cats = a.optJSONArray("categories") ?: JSONArray()
                val ids = mutableListOf<Int>()
                for (c in 0 until cats.length()) {
                    val cat = cats.optJSONObject(c) ?: continue
                    if (cat.optString("type") != "athlete") continue
                    val id = when {
                        cat.has("athleteId") -> cat.optInt("athleteId")
                        else -> cat.optJSONObject("athlete")?.optInt("id") ?: 0
                    }
                    if (id > 0) ids.add(id)
                }
                // An article with no tagged players cannot be joined to
                // anything, so it is not worth storing.
                if (ids.isEmpty()) continue
                add(
                    NewsItem(
                        id = a.optString("id", a.optString("headline")),
                        headline = a.optString("headline"),
                        description = a.optString("description", ""),
                        publishedMillis = parseIso(a.optString("published")),
                        url = a.optJSONObject("links")?.optJSONObject("web")
                            ?.optString("href"),
                        playerIds = ids
                    )
                )
            }
        }
    }

    private fun parseIso(v: String): Long {
        if (v.isBlank()) return 0L
        // Seconds are present in practice ("2026-09-09T23:19:43Z"); the
        // minute-only form is kept as a fallback.
        listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'").forEach { pattern ->
            runCatching {
                val f = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                f.timeZone = java.util.TimeZone.getTimeZone("UTC")
                f.isLenient = false
                f.parse(v)?.time
            }.getOrNull()?.let { return it }
        }
        return 0L
    }
}
