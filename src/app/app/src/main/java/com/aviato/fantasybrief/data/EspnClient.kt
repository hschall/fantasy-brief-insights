package com.aviato.fantasybrief.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Thin read-only client for ESPN's private v3 fantasy API.
 *
 * Deliberately dumb: it builds URLs, attaches the two cookies, and hands
 * back raw text. No parsing here. Parsing happens after the probe has told
 * us what the response actually looks like this season.
 */
class EspnClient(private val secrets: SecretStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Result(
        val code: Int,
        val body: String,
        val error: String? = null,
        val millis: Long = 0
    ) {
        val ok: Boolean get() = code in 200..299
    }

    /** League-scoped endpoint. Note the /segments/0/ path. */
    fun leagueUrl(season: Int, leagueId: Long, vararg views: String): String {
        val base = "$HOST/apis/v3/games/ffl/seasons/$season/segments/0/leagues/$leagueId"
        val query = views.joinToString("&") { "view=$it" }
        return if (query.isEmpty()) base else "$base?$query"
    }

    /**
     * Season-scoped endpoint. NO /segments path — this is where
     * proTeamSchedules_wl lives, and adding /segments returns nothing useful.
     */
    fun seasonUrl(season: Int, vararg views: String): String {
        val base = "$HOST/apis/v3/games/ffl/seasons/$season"
        val query = views.joinToString("&") { "view=$it" }
        return if (query.isEmpty()) base else "$base?$query"
    }

    /**
     * @param fantasyFilter JSON blob for the x-fantasy-filter HEADER.
     *   Player pool and activity queries need this. It is a header, never
     *   a query param — that trips up everyone who tries this API.
     */
    fun get(url: String, fantasyFilter: String? = null): Result {
        if (!secrets.hasCredentials) {
            return Result(-1, "", "No stored cookies — log in first.")
        }

        val request = Request.Builder()
            .url(url)
            .header("Cookie", secrets.cookieHeader())
            .header("Accept", "application/json")
            .header("User-Agent", "FantasyBrief/1.0")
            .also { if (fantasyFilter != null) it.header("x-fantasy-filter", fantasyFilter) }
            .build()

        val started = System.currentTimeMillis()
        return try {
            http.newCall(request).execute().use { response ->
                Result(
                    code = response.code,
                    body = response.body?.string().orEmpty(),
                    millis = System.currentTimeMillis() - started
                )
            }
        } catch (e: Exception) {
            Result(-1, "", e.message ?: e.toString(), System.currentTimeMillis() - started)
        }
    }

    companion object {
        // lm-api-reads is the read-only mirror. There is a write host.
        // Do not use it. This app never writes to ESPN.
        const val HOST = "https://lm-api-reads.fantasy.espn.com"
    }
}
