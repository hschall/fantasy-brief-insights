package com.aviato.fantasybrief.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DIAGNOSTIC, not a parser.
 *
 * The activity log's message fields change meaning with messageTypeId and the
 * mapping is undocumented. This prints every message raw alongside a guessed
 * interpretation so the guess can be checked against transactions we know
 * actually happened. Nothing is written to storage from here.
 */
object ActivityDecoder {

    fun decode(raw: String, league: League?): String {
        val root = JSONObject(raw)
        val topics = root.optJSONArray("topics") ?: return "No topics array."

        val teamName = league?.teams?.associate { it.id to it.name } ?: emptyMap()
        val playerName = league?.teams
            ?.flatMap { it.roster }
            ?.associate { it.playerId to it.name } ?: emptyMap()

        val typeCounts = mutableMapOf<Int, Int>()
        val fieldsByType = mutableMapOf<Int, MutableSet<String>>()
        val sb = StringBuilder()

        sb.append("ACTIVITY DECODE — ").append(topics.length()).append(" topics\n")
        sb.append("Team ids present: ")
            .append(teamName.keys.sorted().joinToString(",")).append("\n\n")

        for (i in 0 until topics.length()) {
            val topic = topics.optJSONObject(i) ?: continue
            val messages = topic.optJSONArray("messages") ?: continue
            val stamp = SimpleDateFormat("MMM d HH:mm", Locale.US)
                .format(Date(topic.optLong("date", 0L)))

            sb.append("--- topic ").append(i)
                .append("  ").append(stamp)
                .append("  ").append(messages.length()).append(" msg")
                .append("  targetId=").append(topic.opt("targetId"))
                .append("\n")

            for (m in 0 until messages.length()) {
                val msg = messages.optJSONObject(m) ?: continue
                val type = msg.optInt("messageTypeId", -1)
                typeCounts[type] = (typeCounts[type] ?: 0) + 1

                // Record which fields each type actually carries. This is the
                // thing that settles the semantics.
                val present = msg.keys().asSequence()
                    .filter { it !in NOISE }
                    .filter { !msg.isNull(it) }
                    .sorted().toList()
                fieldsByType.getOrPut(type) { mutableSetOf() }.addAll(present)

                sb.append("   type=").append(type)
                listOf("for", "from", "to", "targetId").forEach { key ->
                    if (!msg.isNull(key)) {
                        sb.append("  ").append(key).append("=").append(msg.opt(key))
                    }
                }
                sb.append("\n")

                // Guessed reading, printed so it can be contradicted.
                val target = msg.optInt("targetId", -1)
                val known = playerName[target]
                sb.append("      target ").append(target)
                    .append(known?.let { " = $it" } ?: " = (not on any roster now)")
                    .append("\n")

                val forTeam = if (msg.isNull("for")) null else msg.optInt("for")
                val toVal = if (msg.isNull("to")) null else msg.optInt("to")
                val fromVal = if (msg.isNull("from")) null else msg.optInt("from")

                sb.append("      guess: ")
                sb.append(
                    when {
                        toVal == -1 -> "DROP by team ${label(forTeam, teamName)}"
                        fromVal == -1 -> "ADD by team ${label(forTeam, teamName)}"
                        toVal != null && teamName.containsKey(toVal) ->
                            "ADD to ${teamName[toVal]} (to=teamId)"
                        else -> "UNKNOWN"
                    }
                )
                sb.append("\n")
            }
            sb.append("\n")
        }

        sb.append("=== messageTypeId summary ===\n")
        typeCounts.toSortedMap().forEach { (type, count) ->
            sb.append("type ").append(type).append(": ").append(count)
                .append(" msgs, fields: ")
                .append(fieldsByType[type]?.sorted()?.joinToString(","))
                .append("\n")
        }
        return sb.toString()
    }

    private fun label(id: Int?, names: Map<Int, String>): String =
        if (id == null) "?" else names[id]?.let { "$id ($it)" } ?: "$id (unknown)"

    private val NOISE = setOf(
        "id", "topicId", "author", "creationInfo", "date",
        "isDeleted", "isEdited", "isAlternateFormat"
    )
}
