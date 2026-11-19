package com.wakeel.agent.core

import org.json.JSONArray
import org.json.JSONObject

enum class ActionType(val key: String) {
    TAP("tap"), TAP_XY("tap_xy"), LONG_PRESS("long_press"), TYPE("type"), ENTER("enter"),
    SCROLL("scroll"), BACK("back"), HOME("home"), RECENTS("recents"), NOTIFICATIONS("notifications"),
    OPEN_APP("open_app"), WAIT("wait"), SCREENSHOT("screenshot"), ASK_USER("ask_user"),
    DONE("done"), FAIL("fail");

    companion object {
        fun of(k: String?): ActionType? = entries.firstOrNull { it.key == k?.trim()?.lowercase() }
    }
}

data class AgentAction(
    val type: ActionType,
    val id: Int? = null,
    val text: String? = null,
    val dir: String? = null,
    val x: Int? = null,
    val y: Int? = null,
) {
    fun describe(): String = buildString {
        append(type.key)
        id?.let { append(" #").append(it) }
        dir?.let { append(' ').append(it) }
        if (x != null && y != null) append(" (").append(x).append(',').append(y).append(')')
    }
}

data class AgentDecision(val thought: String, val actions: List<AgentAction>, val sensitive: Boolean) {
    companion object {
        fun parse(o: JSONObject): AgentDecision {
            val arr: JSONArray = o.optJSONArray("actions")
                ?: o.optString("actions").takeIf { it.trim().startsWith("[") }?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?: JSONArray()
            val list = mutableListOf<AgentAction>()
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val t = ActionType.of(a.optString("type")) ?: continue
                fun str(k: String) = if (a.has(k) && !a.isNull(k)) a.optString(k).takeIf { it.isNotBlank() } else null
                fun int(k: String) = if (a.has(k) && !a.isNull(k)) a.optDouble(k).takeIf { !it.isNaN() }?.toInt() else null
                list += AgentAction(t, int("id"), str("text"), str("dir")?.lowercase(), int("x"), int("y"))
            }
            return AgentDecision(o.optString("thought"), list.take(5), o.optBoolean("sensitive", false))
        }
    }
}

data class Usage(val input: Int = 0, val output: Int = 0, val cacheRead: Int = 0, val cacheWrite: Int = 0) {
    operator fun plus(o: Usage) = Usage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite)
    val totalIn get() = input + cacheRead + cacheWrite

    fun toJson() = JSONObject().put("i", input).put("o", output).put("cr", cacheRead).put("cw", cacheWrite)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) Usage()
        else Usage(o.optInt("i"), o.optInt("o"), o.optInt("cr"), o.optInt("cw"))
    }
}

enum class LogKind { INFO, THINK, ACTION, SUCCESS, WARN, ERROR, LOCAL }

data class LogEntry(val time: Long, val kind: LogKind, val text: String) {
    fun toJson() = JSONObject().put("t", time).put("k", kind.name).put("x", text)

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(
            o.optLong("t"),
            runCatching { LogKind.valueOf(o.optString("k")) }.getOrDefault(LogKind.INFO),
            o.optString("x"),
        )
    }
}

enum class TaskStatus { RUNNING, DONE, FAILED, STOPPED }

data class TaskRecord(
    val id: Long,
    val task: String,
    val startedAt: Long,
    val endedAt: Long,
    val status: TaskStatus,
    val calls: Int,
    val usage: Usage,
    val cost: Double,
    val steps: Int,
    val localSteps: Int,
    val result: String,
    val logs: List<LogEntry>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("task", task).put("s", startedAt).put("e", endedAt)
        .put("st", status.name).put("calls", calls).put("u", usage.toJson()).put("cost", cost)
        .put("steps", steps).put("local", localSteps).put("res", result)
        .put("logs", JSONArray().also { a -> logs.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): TaskRecord {
            val la = o.optJSONArray("logs") ?: JSONArray()
            return TaskRecord(
                o.optLong("id"), o.optString("task"), o.optLong("s"), o.optLong("e"),
                runCatching { TaskStatus.valueOf(o.optString("st")) }.getOrDefault(TaskStatus.FAILED),
                o.optInt("calls"), Usage.fromJson(o.optJSONObject("u")), o.optDouble("cost", 0.0),
                o.optInt("steps"), o.optInt("local"), o.optString("res"),
                (0 until la.length()).mapNotNull { la.optJSONObject(it)?.let(LogEntry::fromJson) },
            )
        }
    }
}

/** A downscaled JPEG screenshot ready to send, with the factor to map image px back to screen px. */
class Shot(val base64: String, val width: Int, val height: Int, val scaleX: Float, val scaleY: Float)
