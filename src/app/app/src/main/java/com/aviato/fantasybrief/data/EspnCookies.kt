package com.aviato.fantasybrief.data

/** Pure parsing helpers for the ESPN cookie string. No Android dependencies. */
object EspnCookies {

    data class Pair(val espnS2: String, val swid: String)

    /**
     * Pulls espn_s2 and SWID out of a raw "a=1; b=2" cookie string.
     * Returns null unless BOTH are present, because a half-harvest is useless.
     */
    fun parse(raw: String?): Pair? {
        if (raw.isNullOrBlank()) return null

        var espnS2: String? = null
        var swid: String? = null

        for (part in raw.split(";")) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            // substring(eq + 1), not split("="), because espn_s2 contains '='
            val value = part.substring(eq + 1).trim()
            if (value.isEmpty()) continue

            when (name) {
                "espn_s2" -> espnS2 = value
                "SWID" -> swid = normalizeSwid(value)
            }
        }

        val s2 = espnS2 ?: return null
        val id = swid ?: return null
        return Pair(s2, id)
    }

    /** ESPN wants literal braces. Some flows hand them back percent-encoded. */
    fun normalizeSwid(value: String): String {
        var v = value.replace("%7B", "{", ignoreCase = true)
            .replace("%7D", "}", ignoreCase = true)
            .trim()
        if (!v.startsWith("{")) v = "{" + v
        if (!v.endsWith("}")) v = v + "}"
        return v
    }

    /** For the masked display. Never log or show a full cookie. */
    fun mask(value: String?): String {
        if (value.isNullOrBlank()) return "(none)"
        if (value.length <= 12) return ".".repeat(value.length)
        return value.take(6) + "..." + ".".repeat(8) + "..." + value.takeLast(4) +
            "  (" + value.length + " chars)"
    }
}
