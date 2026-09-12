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
    val confidence: String?
)

data class InsightPayload(
    val week: Int?,
    val summary: String,
    val items: List<Insight>,
    val generatedAtMillis: Long?
) {
    val hoursOld: Long?
        get() = generatedAtMillis?.let { (System.currentTimeMillis() - it) / 3_600_000 }
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

    private fun parse(raw: String): InsightPayload? {
        if (raw.isBlank()) return null
        val o = JSONObject(raw)
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            Insight(
                kind = it.optString("kind", "NOTE"),
                playerId = it.optInt("playerId", 0).takeIf { id -> id != 0 },
                headline = it.optString("headline", ""),
                body = it.optString("body", ""),
                confidence = it.optString("confidence", "").ifBlank { null }
            )
        }
        // Firestore serialises timestamps as {_seconds, _nanoseconds}.
        val ts = o.optJSONObject("generatedAt")?.optLong("_seconds")?.times(1000)
            ?: o.optString("generatedAt", "").takeIf { it.isNotBlank() }?.let {
                runCatching {
                    val f = java.text.SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    f.parse(it)?.time
                }.getOrNull()
            }
        return InsightPayload(
            week = o.optInt("week", 0).takeIf { it != 0 },
            summary = o.optString("summary", ""),
            items = items,
            generatedAtMillis = ts
        )
    }
}
