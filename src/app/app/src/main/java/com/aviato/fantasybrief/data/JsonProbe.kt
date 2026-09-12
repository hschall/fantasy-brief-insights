package com.aviato.fantasybrief.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Prints the SHAPE of a JSON response, not its contents.
 *
 * The ESPN API is undocumented and its shapes shift between seasons, so the
 * habit is: probe first, write data classes second. Arrays show their length
 * and then descend into element [0] only, because a 10-team roster response
 * is 40,000 lines and all ten elements have the same shape.
 */
object JsonProbe {

    fun outline(raw: String, maxDepth: Int = 3): String {
        val sb = StringBuilder()
        val trimmed = raw.trim()
        try {
            when {
                trimmed.startsWith("{") -> walk(JSONObject(trimmed), "", 0, maxDepth, sb)
                trimmed.startsWith("[") -> walk(JSONArray(trimmed), "", 0, maxDepth, sb)
                else -> sb.append("Not JSON. First 300 chars:\n")
                    .append(trimmed.take(300))
            }
        } catch (e: Exception) {
            sb.append("Parse failed: ").append(e.message).append("\n\n")
                .append("First 300 chars:\n").append(trimmed.take(300))
        }
        return sb.toString()
    }

    private fun walk(node: Any?, path: String, depth: Int, maxDepth: Int, sb: StringBuilder) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().sorted().toList()
                if (depth >= maxDepth) {
                    sb.append(indent(depth)).append(path).append("  {")
                        .append(keys.size).append(" keys: ")
                        .append(keys.take(15).joinToString(", "))
                        .append(if (keys.size > 15) ", ..." else "").append("}\n")
                    return
                }
                for (key in keys) {
                    val child = if (path.isEmpty()) key else "$path.$key"
                    when (val value = node.opt(key)) {
                        is JSONObject -> {
                            sb.append(indent(depth)).append(child).append("  {obj}\n")
                            walk(value, child, depth + 1, maxDepth, sb)
                        }
                        is JSONArray -> {
                            sb.append(indent(depth)).append(child)
                                .append("  [").append(value.length()).append("]\n")
                            if (value.length() > 0) {
                                walk(value.opt(0), "$child[0]", depth + 1, maxDepth, sb)
                            }
                        }
                        else -> sb.append(indent(depth)).append(child)
                            .append("  = ").append(preview(value)).append("\n")
                    }
                }
            }
            is JSONArray -> {
                sb.append(indent(depth)).append(path)
                    .append("  [").append(node.length()).append("]\n")
                if (node.length() > 0) {
                    walk(node.opt(0), "$path[0]", depth + 1, maxDepth, sb)
                }
            }
            else -> sb.append(indent(depth)).append(path)
                .append("  = ").append(preview(node)).append("\n")
        }
    }

    private fun indent(depth: Int) = "  ".repeat(depth)

    private fun preview(value: Any?): String {
        val text = value?.toString() ?: "null"
        val type = when (value) {
            null, JSONObject.NULL -> "null"
            is Boolean -> "bool"
            is Int, is Long -> "int"
            is Double, is Float -> "num"
            else -> "str"
        }
        return if (text.length > 40) "$type ${text.take(40)}..." else "$type $text"
    }
}
