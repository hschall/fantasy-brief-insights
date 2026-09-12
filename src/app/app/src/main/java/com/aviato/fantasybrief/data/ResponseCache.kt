package com.aviato.fantasybrief.data

import android.content.Context
import java.io.File

/**
 * Disk cache for raw response bodies, keyed with a TTL.
 *
 * The app was refetching everything on every cold start, including 32
 * sequential depth-chart requests. Most of that data does not change on the
 * timescale of an app launch: league rules never change in-season, bye weeks
 * never change, depth charts move weekly at most.
 *
 * TTLs are deliberately shorter than the real change rate, because being a
 * few hours stale on a depth chart is cheap and being stale on something that
 * moved is not.
 */
class ResponseCache(context: Context) {

    private val dir = File(context.filesDir, "httpcache").apply { mkdirs() }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    fun get(key: String, ttlMillis: Long): String? {
        val f = file(key)
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > ttlMillis) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun put(key: String, body: String) {
        if (body.isBlank()) return
        runCatching { file(key).writeText(body) }
    }

    fun ageMillis(key: String): Long? {
        val f = file(key)
        return if (f.exists()) System.currentTimeMillis() - f.lastModified() else null
    }

    fun clear() = runCatching { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        const val TTL_SEASON = 7L * 24 * 60 * 60 * 1000    // byes, fixtures
        const val TTL_DAY = 24L * 60 * 60 * 1000           // depth charts
        const val TTL_HALF_DAY = 12L * 60 * 60 * 1000      // matchup schedule
    }
}
