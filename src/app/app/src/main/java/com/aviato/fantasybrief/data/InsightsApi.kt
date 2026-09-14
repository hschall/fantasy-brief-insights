package com.aviato.fantasybrief.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One analysis item, written by Claude and rendered by the app.
 *
 * Structured rather than prose: a blob of text in a tab is barely better than
 * reading it in chat. Keyed to a playerId, the app can attach it to his card,
 * sort by kind, and put a start/sit call next to the actual lineup.
 */
data class Insight(
    val kind: String,
    val playerId: Int?,
    val headline: String,
    val body: String,
    val confidence: String?,
    /** Which block of the screen this belongs in. See Section. */
    val section: String = Section.NOTE,
    /** How much acting on this is worth. Drives sort order. See Impact. */
    val impact: String = Impact.SOLID,
    /** Projected points swung by acting, when that is a meaningful number. */
    val impactPoints: Double? = null,
    /** The pill: PLAYS, PLAYS CAPPED, DOUBTFUL, OUT, UNRESOLVED. */
    val verdict: String? = null,
    /** What the verdict rests on, in a line or two. */
    val evidence: String? = null,
    /** When the reporting behind this was published — not when I wrote it. */
    val sourceAtMillis: Long? = null,
    /** After this, the card greys and the button refuses. Null = no deadline. */
    val expiresAtMillis: Long? = null,
    val action: InsightAction? = null
) {
    fun isExpired(now: Long) = expiresAtMillis != null && now >= expiresAtMillis

    /**
     * A button only fires inside its window.
     *
     * Tapping ADD on a Friday verdict at Sunday noon, after the player has been
     * downgraded, is worse than having no button: the screen lent authority to
     * something it had no way to re-check. An expired card still shows — the
     * reasoning may still be worth reading — but it shows greyed and inert.
     */
    fun isActionable(now: Long) = action != null && !isExpired(now)
}

/**
 * The names the app and the payload agree on.
 *
 * Constants rather than an enum: an unknown section from a future payload must
 * degrade to NOTE rather than throw, and the screen has to keep working when
 * the writer is ahead of the reader.
 */
/**
 * How much this is worth, which is what decides where it sits.
 *
 * Ordering by section buries a season-changing item under a routine one, and
 * ordering by deadline puts a note that goes stale at one o'clock above a
 * claim worth ten points. Neither is what you want from a screen you open for
 * thirty seconds. Value first, deadline only to break ties inside a tier.
 *
 * The names match the wire tiers on purpose — same vocabulary, same visual
 * treatment, so LEGENDARY reads the same here as it does on a player tile.
 */
object Impact {
    /** Changes the season. A league error, or a trade that fixes a bye cluster. */
    const val LEGENDARY = "LEGENDARY"
    /** Changes this week. A starter swap, or a claim that upgrades a starting slot. */
    const val ELITE = "ELITE"
    /** Worth doing. Bench depth, a speculative handcuff. */
    const val SOLID = "SOLID"
    /** Nothing to do. Renders greyed from the start. */
    const val WATCH = "WATCH"

    private val order = listOf(LEGENDARY, ELITE, SOLID, WATCH)
    fun rank(s: String) = order.indexOf(s).takeIf { it >= 0 } ?: order.size
}

/**
 * When a note was written, in the reader's own timezone.
 *
 * Device local on purpose: "how old is this" is a question about where you are
 * standing right now, unlike the Calendar, which is a plan and stays pinned.
 */
fun notePostedLabel(millis: Long?): String? = millis?.let {
    java.time.Instant.ofEpochMilli(it)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern(
            "EEE d MMM, h:mm a", java.util.Locale.US))
        .replace("AM", "am").replace("PM", "pm")
}

object Section {
    const val DO_NOW = "DO_NOW"
    const val AT_RISK = "AT_RISK"
    const val LINEUP = "LINEUP"
    const val MATCHUP = "MATCHUP"
    const val BAD_NEWS = "BAD_NEWS"
    const val TRADE = "TRADE"
    const val SETTLED = "SETTLED"
    const val NOTE = "NOTE"

    /** Screen order. Anything unrecognised sorts last. */
    val order = listOf(DO_NOW, AT_RISK, LINEUP, MATCHUP, BAD_NEWS, TRADE, SETTLED, NOTE)
    fun rank(s: String) = order.indexOf(s).takeIf { it >= 0 } ?: order.size
}

/**
 * What the button does. The app owns the confirmation and the audit entry;
 * this only says what to confirm.
 */
data class InsightAction(
    /** ADD, CLAIM, DROP, BENCH, START, SWAP, PROPOSE. */
    val type: String,
    val playerId: Int?,
    /** The player going the other way. Named, never implied. */
    val dropPlayerId: Int?,
    val label: String
)

data class InsightPayload(
    val week: Int?,
    val summary: String,
    val items: List<Insight>,
    val generatedAtMillis: Long?
) {
    val hoursOld: Long?
        get() = generatedAtMillis?.let { (System.currentTimeMillis() - it) / 3_600_000 }

    /**
     * Value first, deadline second, expired last.
     *
     * An expired item sinks below everything live no matter how valuable it
     * was — its window has closed, so its old rank is a lie about what you can
     * still do. It still shows, greyed, because the reasoning may be worth
     * reading; it just stops competing for the top of the screen.
     *
     * `now` is passed in so the whole screen agrees on one instant.
     */
    fun ordered(now: Long): List<Insight> = items.sortedWith(
        compareBy<Insight> { if (it.isExpired(now)) 1 else 0 }
            .thenBy { Impact.rank(it.impact) }
            .thenBy { it.expiresAtMillis ?: Long.MAX_VALUE }
    )

    /** Grouped for display; each group keeps the ordering above. */
    fun bySection(now: Long): Map<String, List<Insight>> =
        ordered(now).groupBy { it.section }

    fun liveCount(now: Long) = items.count { !it.isExpired(now) }
}

/**
 * The bridge to the analysis endpoint.
 *
 * The phone cannot host anything reachable, so a small Cloud Function holds
 * the data instead: the app POSTs its dump there, Claude reads it, researches,
 * and POSTs an analysis back for the app to collect.
 *
 * Auth is a shared secret. The payload is fantasy football and the worst case
 * is somebody reading a roster, so a header token is proportionate.
 */
class InsightsApi(private val secrets: SecretStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    private companion object {
        const val RAW =
            "https://raw.githubusercontent.com/hschall/fantasy-brief-insights/main"
    }

    fun uploadBrief(
        leagueId: Long,
        dump: String,
        week: Int,
        teamName: String,
        /** "daily" or "weekly" — separate documents, both readable. */
        kind: String = "daily"
    ): Boolean {
        val base = secrets.apiUrl ?: return false
        val key = secrets.apiKey ?: return false
        val body = JSONObject().apply {
            put("dump", dump); put("week", week); put("teamName", teamName)
        }.toString().toRequestBody(json)

        return runCatching {
            http.newCall(
                Request.Builder()
                    .url("$base/brief?leagueId=$leagueId&kind=$kind")
                    .header("x-api-key", key)
                    .post(body)
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Set on the last failure so the UI can say what went wrong. */
    var lastError: String? = null
        private set

    fun fetchInsights(leagueId: Long): InsightPayload? {
        // Published by Claude, served by the CDN, no auth needed: the
        // contents are fantasy analysis. Cached about five minutes.
        val url = "$RAW/insights-$leagueId.json"
        return runCatching {
            http.newCall(Request.Builder().url(url).get().build())
                .execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        lastError = "HTTP ${res.code}"
                        return null
                    }
                    lastError = null
                    parse(body)
                }
        }.onFailure { lastError = it.toString().take(160) }.getOrNull()
    }

    /**
     * The daily brief. Separate file from insights-<id>.json on purpose.
     *
     * Different shape, different lifecycle and a different writer. Sharing a
     * filename would mean one publish silently truncating the other, and the
     * old payload still feeds the lock badges while the new screen is built.
     */
    fun fetchDailyBrief(leagueId: Long): DailyBrief? {
        val url = "$RAW/daily-$leagueId.json"
        return runCatching {
            http.newCall(Request.Builder().url(url).get().build())
                .execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        // A 404 is the ordinary state before the first
                        // publish, not a fault worth showing the user.
                        lastError = if (res.code == 404) null else "HTTP ${res.code}"
                        return null
                    }
                    lastError = null
                    DailyBriefParser.parse(body)
                }
        }.onFailure { lastError = it.toString().take(160) }.getOrNull()
    }

    private fun fetchInsightsViaApi(leagueId: Long): InsightPayload? {
        val base = secrets.apiUrl
        val key = secrets.apiKey
        if (base.isNullOrBlank() || key.isNullOrBlank()) {
            lastError = "url or key missing"
            return null
        }
        return runCatching {
            http.newCall(
                Request.Builder()
                    .url("$base/insights?leagueId=$leagueId")
                    .header("x-api-key", key)
                    .get()
                    .build()
            ).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    lastError = "HTTP ${res.code}: ${body.take(120)}"
                    return null
                }
                lastError = null
                parse(body)
            }
        }.onFailure { lastError = it.toString().take(160) }.getOrNull()
    }

    /**
     * Instant.parse, not SimpleDateFormat.
     *
     * The pattern this replaces was pinned to "...HH:mm:ss'Z'" and returned
     * null against "...16:00:02.080Z" — a missing timestamp reads as "no
     * deadline", which is the most dangerous possible failure for a field that
     * exists to stop a stale button firing. Instant handles both forms, and
     * minSdk is 26 so it needs no desugaring.
     */
    private fun isoMillis(s: String): Long? =
        s.takeIf { it.isNotBlank() }
            ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun parse(raw: String): InsightPayload? {
        if (raw.isBlank()) return null
        val o = JSONObject(raw)
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            val act = it.optJSONObject("action")?.let { a ->
                InsightAction(
                    type = a.optString("type", ""),
                    playerId = a.optInt("playerId", 0).takeIf { id -> id != 0 },
                    dropPlayerId = a.optInt("dropPlayerId", 0).takeIf { id -> id != 0 },
                    label = a.optString("label", "").ifBlank { a.optString("type", "Go") }
                ).takeIf { p -> p.type.isNotBlank() }
            }
            Insight(
                kind = it.optString("kind", "NOTE"),
                playerId = it.optInt("playerId", 0).takeIf { id -> id != 0 },
                headline = it.optString("headline", ""),
                body = it.optString("body", ""),
                confidence = it.optString("confidence", "").ifBlank { null },
                section = it.optString("section", "").ifBlank { Section.NOTE },
                impact = it.optString("impact", "").ifBlank { Impact.SOLID },
                impactPoints = it.optDouble("impactPoints", Double.NaN)
                    .takeIf { v -> !v.isNaN() },
                verdict = it.optString("verdict", "").ifBlank { null },
                evidence = it.optString("evidence", "").ifBlank { null },
                sourceAtMillis = isoMillis(it.optString("sourceAt", "")),
                expiresAtMillis = isoMillis(it.optString("expiresAt", "")),
                action = act
            )
        }
        // Firestore serialises timestamps as {_seconds, _nanoseconds}.
        val ts = o.optJSONObject("generatedAt")?.optLong("_seconds")?.times(1000)
            ?: isoMillis(o.optString("generatedAt", ""))
        return InsightPayload(
            week = o.optInt("week", 0).takeIf { it != 0 },
            summary = o.optString("summary", ""),
            items = items,
            generatedAtMillis = ts
        )
    }
}
