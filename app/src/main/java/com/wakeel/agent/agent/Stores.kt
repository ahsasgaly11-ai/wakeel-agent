package com.wakeel.agent.agent

import android.content.Context
import com.wakeel.agent.core.TaskRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One step of a saved route: replayed later with zero API calls. */
data class MacroStep(
    val type: String,
    val sig: String? = null,
    val text: String? = null,
    val dir: String? = null,
    val label: String? = null,
    val confirm: Boolean = false,
    /** The owner chose "always allow" for this step of this skill: it is carried out without asking again. */
    val approved: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("t", type).apply {
        sig?.let { put("s", it) }; text?.let { put("x", it) }; dir?.let { put("d", it) }
        label?.let { put("l", it) }; if (confirm) put("c", true); if (approved) put("a", true)
    }

    companion object {
        fun fromJson(o: JSONObject) = MacroStep(
            o.optString("t"),
            o.optString("s").ifEmpty { null },
            o.optString("x").ifEmpty { null },
            o.optString("d").ifEmpty { null },
            o.optString("l").ifEmpty { null },
            o.optBoolean("c", false),
            o.optBoolean("a", false),
        )
    }
}

class HistoryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<TaskRecord>> = _records.asStateFlow()

    private fun load(): List<TaskRecord> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(TaskRecord::fromJson) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(r: TaskRecord) {
        val list = (listOf(r) + _records.value).take(60)
        _records.value = list
        runCatching { file.writeText(JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()) }
    }

    @Synchronized
    fun clear() {
        _records.value = emptyList()
        runCatching { file.delete() }
    }
}
