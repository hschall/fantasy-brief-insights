package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How much weight a note carries. Known labels only; the rest is context. */
enum class NoteKind(val label: String) {
    LOCK("LINEUP LOCK"),
    GOOD_MATCHUP("GOOD MATCHUP"),
    AVOID("AVOID"),
    SCOOP("SCOOP"),
    MENTION("MENTIONED")
}

data class AnalystNote(
    /** Every player linked in the paragraph, in document order. */
    val playerIds: List<Int>,
    val kind: NoteKind,
    val text: String,
    val byline: String,
    val articleId: String,
    val publishedMillis: Long
) {
    val hoursAgo: Long get() = (System.currentTimeMillis() - publishedMillis) / 3_600_000

    /**
     * The first link is the subject of a prose paragraph. A LOCK is a
     * list, so every name in it is equally the subject.
     */
    fun isAbout(playerId: Int) =
        kind == NoteKind.LOCK || playerIds.firstOrNull() == playerId

    /**
     * A lock list is just names — the LABEL is the information, so there
     * is no prose worth showing. Everything else carries reasoning.
     */
    val hasProse: Boolean get() = kind != NoteKind.LOCK
}

/**
 * Fantasy analysis from ESPN's weekly columns, joined to players by id.
 *
 * VERIFIED 2026-09-09, and every step of this is a fact we probed rather than
 * assumed:
 *   - The NFL news feed carries the fantasy columns and caps at 50 articles.
 *     Bylines are populated ("Mike Clay", "Tristan H. Cockcroft"), which is
 *     far more stable to match on than headline text that changes weekly.
 *   - content.core.api.espn.com serves the full story HTML, not premium.
 *   - Player links in the body carry player/_/id/{athleteId}, and that id is
 *     the SAME namespace as the fantasy playerId.
 *   - The columns have no tables. They use labelled paragraph prefixes:
 *     "Lineup locks:", "Fantasy scoop:", "Matchups highlight:",
 *     "Matchup to avoid:", "Others to like:".
 *
 * THIS IS SCRAPING and it will break when ESPN restyles. So it keeps ANY
 * paragraph containing a player link and only categorises the ones whose
 * label it recognises — a new section shows up as a plain mention rather than
 * vanishing. When the parse fails entirely the app shows nothing, never a
 * wrong answer.
 */
class AnalystStore(context: Context) {

    private val file = File(context.filesDir, "analyst_notes.json")

    fun all(): List<AnalystNote> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val ids = o.optJSONArray("p") ?: JSONArray()
                    add(
                        AnalystNote(
                            playerIds = (0 until ids.length()).map { ids.optInt(it) },
                            kind = runCatching { NoteKind.valueOf(o.optString("k")) }
                                .getOrDefault(NoteKind.MENTION),
                            text = o.optString("t"),
                            byline = o.optString("b"),
                            articleId = o.optString("a"),
                            publishedMillis = o.optLong("d")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(notes: List<AnalystNote>) {
        val cutoff = System.currentTimeMillis() - RETENTION
        val arr = JSONArray()
        // publishedMillis == 0 means the timestamp did not parse. Keep the
        // note rather than treating it as ancient — a date bug should not
        // silently discard real content.
        notes.filter { it.publishedMillis == 0L || it.publishedMillis >= cutoff }
            .take(400).forEach { n ->
            val ids = JSONArray()
            n.playerIds.forEach { ids.put(it) }
            arr.put(
                JSONObject().apply {
                    put("p", ids); put("k", n.kind.name); put("t", n.text)
                    put("b", n.byline); put("a", n.articleId); put("d", n.publishedMillis)
                }
            )
        }
        file.writeText(arr.toString())
    }

    fun merge(fresh: List<AnalystNote>) {
        if (fresh.isEmpty()) return
        // Keyed on article plus text so a re-fetch of the same column does not
        // duplicate every paragraph.
        val known = all().associateBy { it.articleId + "|" + it.text.take(60) }
            .toMutableMap()
        fresh.forEach { known[it.articleId + "|" + it.text.take(60)] = it }
        save(known.values.sortedByDescending { it.publishedMillis })
    }

    /** playerId -> his notes, strongest kind first. */
    fun byPlayer(): Map<Int, List<AnalystNote>> {
        val out = mutableMapOf<Int, MutableList<AnalystNote>>()
        all().forEach { n ->
            n.playerIds.forEach { out.getOrPut(it) { mutableListOf() }.add(n) }
        }
        return out.mapValues { (id, v) ->
            v.sortedWith(
                compareByDescending<AnalystNote> { it.isAbout(id) }
                    .thenBy { it.kind.ordinal }
                    .thenByDescending { it.publishedMillis }
            )
        }
    }

    private companion object {
        const val RETENTION = 4L * 24 * 60 * 60 * 1000
    }
}

object AnalystClient {

    /** Bylines worth fetching. Matching these beats matching headline text. */
    private val WANTED = listOf(
        "mike clay", "tristan h. cockcroft", "eric karabell",
        "field yates", "espn fantasy", "fantasy staff"
    )

    private val http = PublicEspnClient()

    /** Set by fetchNotes so a silent empty result can be explained. */
    var diagnostic: String = "not run"
        private set

    fun fetchNotes(knownArticleIds: Set<String> = emptySet()): List<AnalystNote> {
        val feed = http.get(
            "${PublicEspnClient.SITE}/apis/site/v2/sports/football/nfl/news?limit=50"
        )
        if (!feed.ok) {
            diagnostic = "feed HTTP ${feed.code} ${feed.error ?: ""}"
            return emptyList()
        }

        val ids = runCatching { fantasyArticles(feed.body) }
            .onFailure { diagnostic = "feed parse: ${it.message}" }
            .getOrDefault(emptyList())
        if (ids.isEmpty()) {
            diagnostic = "feed ok (${feed.body.length} chars) but 0 fantasy articles"
            return emptyList()
        }

        var bodiesOk = 0
        var storyChars = 0
        var paraCount = 0
        val out = mutableListOf<AnalystNote>()
        var skipped = 0
        ids.forEach { (id, byline, published) ->
            // Already parsed and stored: nothing to gain by fetching it.
            if (id in knownArticleIds) { skipped++; return@forEach }
            val body = http.get("$CONTENT/v1/sports/news/$id")
            if (!body.ok) return@forEach
            bodiesOk++
            val story = runCatching {
                JSONObject(body.body).optJSONArray("headlines")
                    ?.optJSONObject(0)?.optString("story").orEmpty()
            }.getOrDefault("")
            storyChars += story.length
            paraCount += PARA.findAll(story).count()
            out += runCatching { parseBody(body.body, id, byline, published) }
                .onFailure { diagnostic = "body parse: ${it.message}" }
                .getOrDefault(emptyList())
        }
        diagnostic = "${ids.size} articles, $skipped cached, $bodiesOk fetched, " +
            "$storyChars story chars, $paraCount paragraphs, ${out.size} notes"
        return out
    }

    private data class Ref(val id: String, val byline: String, val published: Long)

    private fun fantasyArticles(raw: String): List<Ref> {
        val arts = JSONObject(raw).optJSONArray("articles") ?: return emptyList()
        return buildList {
            for (i in 0 until arts.length()) {
                val a = arts.optJSONObject(i) ?: continue
                val byline = a.optString("byline", "")
                val head = a.optString("headline", "")
                val fantasy = byline.lowercase() in WANTED ||
                    head.lowercase().contains("fantasy")
                if (!fantasy) continue
                add(
                    Ref(
                        a.optString("id"),
                        byline.ifBlank { "ESPN" },
                        parseIso(a.optString("published"))
                    )
                )
            }
        }.distinctBy { it.id }.take(6)   // six columns is plenty per poll
    }

    fun parseBody(
        raw: String,
        articleId: String,
        byline: String,
        published: Long
    ): List<AnalystNote> {
        val story = JSONObject(raw).optJSONArray("headlines")
            ?.optJSONObject(0)?.optString("story") ?: return emptyList()

        val paras = PARA.findAll(story).map { it.groupValues[1] }
        return paras.mapNotNull { html ->
            val ids = LINK.findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }
                .distinct().toList()
            // No player link means nothing to join it to.
            if (ids.isEmpty()) return@mapNotNull null
            val text = strip(html)
            // A bare ranking line ("1. Jahmyr Gibbs, RB1 vs NO") is already
            // covered by the structured rankings and carries no reasoning.
            if (text.length < 40) return@mapNotNull null
            AnalystNote(ids, classify(text), text, byline, articleId, published)
        }.toList()
    }

    /** Known labels get a kind; everything else is a plain mention. */
    private fun classify(text: String): NoteKind {
        val t = text.lowercase()
        return when {
            t.startsWith("lineup lock") -> NoteKind.LOCK
            t.startsWith("matchup to avoid") -> NoteKind.AVOID
            t.startsWith("matchups highlight") -> NoteKind.GOOD_MATCHUP
            t.startsWith("others to like") -> NoteKind.GOOD_MATCHUP
            t.startsWith("fantasy scoop") -> NoteKind.SCOOP
            else -> NoteKind.MENTION
        }
    }

    private fun strip(html: String): String =
        html.replace(TAG, "")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&rsquo;", "'").replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"")
            .trim()

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

    private const val CONTENT = "https://content.core.api.espn.com"
    private val PARA = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val LINK = Regex("player/_/id/(\\d+)")
    private val TAG = Regex("<[^>]+>")
}
