package com.wakeel.agent.agent

import android.content.Context
import com.wakeel.agent.core.Text
import com.wakeel.agent.core.Usage
import com.wakeel.agent.llm.LlmClient
import com.wakeel.agent.llm.ToolSpec
import com.wakeel.agent.service.Screen
import com.wakeel.agent.service.UiNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A learned, reusable task. `pattern` is the task with {slots}; steps may contain {slot} in text/label.
 * Replayed locally with zero API calls once trusted.
 */
data class Skill(
    val id: String,
    val title: String,
    val pattern: String,
    val params: List<String>,
    val example: String,
    val steps: List<MacroStep>,
    val doneTpl: String,
    val readout: Boolean,
    val successes: Int = 0,
    val fails: Int = 0,
    val uses: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsed: Long = 0,
    /** The app this skill mainly works in. */
    val pkg: String = "",
    /** Recorded from the owner's own hands (not from a run the agent completed itself). */
    val taught: Boolean = false,
) {
    /** Trusted skills finish with zero API calls (no verification step). */
    val trusted: Boolean get() = successes >= 2 && fails * 3 <= successes

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("pattern", pattern)
        .put("params", JSONArray(params)).put("example", example)
        .put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })
        .put("done", doneTpl).put("readout", readout)
        .put("ok", successes).put("fail", fails).put("uses", uses)
        .put("created", createdAt).put("last", lastUsed).put("pkg", pkg).put("taught", taught)

    companion object {
        fun fromJson(o: JSONObject): Skill {
            val pa = o.optJSONArray("params") ?: JSONArray()
            val sa = o.optJSONArray("steps") ?: JSONArray()
            return Skill(
                o.optString("id"), o.optString("title"), o.optString("pattern"),
                (0 until pa.length()).map { pa.optString(it) }, o.optString("example"),
                (0 until sa.length()).mapNotNull { sa.optJSONObject(it)?.let(MacroStep::fromJson) },
                o.optString("done"), o.optBoolean("readout"),
                o.optInt("ok"), o.optInt("fail"), o.optInt("uses"), o.optLong("created"), o.optLong("last"), o.optString("pkg"),
                o.optBoolean("taught", false),
            )
        }
    }
}

object Templates {
    private val slot = Regex("\\{([^{}]{1,40})\\}")

    fun fill(tpl: String?, params: Map<String, String>): String? {
        if (tpl == null) return null
        var s: String = tpl
        params.forEach { (k, v) -> s = s.replace("{$k}", v) }
        return s
    }

    fun isTemplated(s: String?) = s != null && slot.containsMatchIn(s)

    /** Replaces concrete parameter values with {slot} placeholders. */
    fun templatize(s: String?, params: Map<String, String>): String? {
        if (s.isNullOrEmpty()) return s
        val nS = Text.norm(s)
        for ((k, v) in params.entries.sortedByDescending { it.value.length }) {
            if (v.length < 2) continue
            if (nS == Text.norm(v)) return "{$k}"
        }
        var out: String = s
        for ((k, v) in params.entries.sortedByDescending { it.value.length }) {
            if (v.length < 2) continue
            out = out.replace(v, "{$k}", ignoreCase = true)
            // The same value inside a link is percent-encoded
            val enc = runCatching { java.net.URLEncoder.encode(v, "UTF-8") }.getOrNull() ?: continue
            if (enc != v) {
                out = out.replace(enc, "{$k}", ignoreCase = true)
                out = out.replace(enc.replace("+", "%20"), "{$k}", ignoreCase = true)
            }
        }
        return out
    }

    fun uses(step: MacroStep, slot: String): Boolean =
        step.text?.contains("{$slot}") == true || step.label?.contains("{$slot}") == true

    // ---- Pattern → tolerant regex (Arabic letter variants, diacritics, spacing) ----
    private fun fuzzy(literal: String): String {
        val b = StringBuilder()
        for (ch in Text.norm(literal)) {
            if (ch == ' ') { b.append("\\s+"); continue }
            b.append(
                when (ch) {
                    'ا' -> "[اأإآٱ]"
                    'ه' -> "[هة]"
                    'ي' -> "[يىئ]"
                    'و' -> "[وؤ]"
                    ',' -> "[,،]"
                    in '0'..'9' -> "[" + ch + ('٠' + (ch - '0')) + "]"
                    else -> Regex.escape(ch.toString())
                }
            )
            b.append("[\\u064B-\\u0652\\u0670\\u0640]*")
        }
        return b.toString()
    }

    fun match(pattern: String, task: String): Map<String, String>? {
        val names = mutableListOf<String>()
        val sb = StringBuilder("^\\s*")
        var last = 0
        for (m in slot.findAll(pattern)) {
            sb.append(fuzzy(pattern.substring(last, m.range.first)))
            sb.append("(.+?)")
            names += m.groupValues[1].trim()
            last = m.range.last + 1
        }
        if (names.isEmpty()) return null
        sb.append(fuzzy(pattern.substring(last)))
        sb.append("\\s*[.!؟?]*\\s*$")
        val r = runCatching { Regex(sb.toString(), RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        val m = r.find(task.trim()) ?: return null
        val out = LinkedHashMap<String, String>()
        names.forEachIndexed { i, n -> out[n] = m.groupValues[i + 1].trim() }
        return out.takeIf { it.values.all { v -> v.isNotBlank() } }
    }

    fun fixedWords(pattern: String): Set<String> =
        Text.norm(pattern.replace(slot, " ")).split(' ').filter { it.length >= 2 }.toSet()
}

class SkillStore(ctx: Context) {
    private val file = File(ctx.filesDir, "skills.json")
    private val _skills = MutableStateFlow(load())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    private fun load(): List<Skill> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Skill::fromJson) }
    }.getOrDefault(emptyList())

    private fun persist(list: List<Skill>) {
        _skills.value = list
        runCatching { file.writeText(JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()) }
    }

    /** Zero-cost local match: exact example, then slot patterns. */
    @Synchronized
    fun localMatch(task: String): Pair<Skill, Map<String, String>>? {
        val n = Text.norm(task)
        val list = _skills.value.sortedByDescending { it.successes }
        list.firstOrNull { Text.norm(it.example) == n || (it.params.isEmpty() && Text.norm(it.pattern) == n) }
            ?.let { return it to emptyMap() }
        for (s in list) {
            if (s.params.isEmpty()) continue
            Templates.match(s.pattern, task)?.let { return s to it }
        }
        return null
    }

    /** Skills worth asking the cheap router about (word overlap with the task). */
    fun candidates(task: String, max: Int = 8): List<Skill> {
        val words = Text.norm(task).split(' ').filter { it.length >= 2 }.toSet()
        if (words.isEmpty()) return emptyList()
        return _skills.value.mapNotNull { s ->
            val fw = Templates.fixedWords(s.pattern)
            if (fw.isEmpty()) return@mapNotNull null
            val score = fw.count { it in words }.toDouble() / fw.size
            if (score >= 0.5) s to score else null
        }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    fun byId(id: String) = _skills.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(skill: Skill) {
        val np = Text.norm(skill.pattern)
        val ne = Text.norm(skill.example)
        val old = _skills.value.firstOrNull { Text.norm(it.pattern) == np || Text.norm(it.example) == ne }
        val merged = if (old != null) skill.copy(id = old.id, successes = old.successes + 1, uses = old.uses, createdAt = old.createdAt, fails = 0)
        else skill.copy(successes = 1)
        persist(_skills.value.filter { it.id != old?.id } + merged)
    }

    @Synchronized
    fun record(id: String, success: Boolean) {
        val list = _skills.value.map {
            if (it.id != id) it
            else it.copy(
                successes = it.successes + if (success) 1 else 0,
                fails = it.fails + if (success) 0 else 1,
                uses = it.uses + 1, lastUsed = System.currentTimeMillis(),
            )
        }.filterNot { it.fails >= 3 && it.fails > it.successes }   // forget skills that keep breaking
        persist(list)
    }

    /** Saves a skill exactly as given (one the owner taught by hand), replacing any skill with the same phrase. */
    @Synchronized
    fun put(skill: Skill) {
        val np = Text.norm(skill.pattern)
        val ne = Text.norm(skill.example)
        persist(_skills.value.filterNot { Text.norm(it.pattern) == np || Text.norm(it.example) == ne } + skill)
    }

    /** "Always allow" for one step of one skill. Re-learning the skill asks again. */
    @Synchronized
    fun approveStep(id: String, index: Int) {
        persist(_skills.value.map { sk ->
            if (sk.id != id || index !in sk.steps.indices) sk
            else sk.copy(steps = sk.steps.mapIndexed { i, st -> if (i == index) st.copy(approved = true) else st })
        })
    }

    fun forPackage(pkg: String): List<Skill> = _skills.value.filter { it.pkg == pkg }
    fun remove(id: String) = persist(_skills.value.filter { it.id != id })
    fun clear() = persist(emptyList())
}

/** Short learned tips per app ("Edit profile is under the Profile tab"), injected into prompts for that app. */
class AppNotes(ctx: Context) {
    private val file = File(ctx.filesDir, "app_notes.json")
    private val data: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private val _count = MutableStateFlow(total())
    val count: StateFlow<Int> = _count.asStateFlow()

    private fun total() = data.keys().asSequence().sumOf { data.optJSONArray(it)?.length() ?: 0 }

    @Synchronized
    fun get(pkg: String): List<String> {
        val a = data.optJSONArray(pkg) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    @Synchronized
    fun add(pkg: String, notes: List<String>) {
        if (pkg.isBlank() || notes.isEmpty()) return
        val existing = get(pkg)
        val seen = existing.map { Text.norm(it) }.toMutableSet()
        val fresh = notes.map { it.trim().take(160) }.filter { it.length > 5 && seen.add(Text.norm(it)) }
        if (fresh.isEmpty()) return
        data.put(pkg, JSONArray((fresh + existing).take(10)))
        save()
    }

    @Synchronized
    fun clear() {
        data.keys().asSequence().toList().forEach { data.remove(it) }
        save()
    }

    @Synchronized
    fun clear(pkg: String) {
        data.remove(pkg)
        save()
    }

    private fun save() {
        runCatching { file.writeText(data.toString()) }
        _count.value = total()
    }
}

/** Two cheap model calls: generalize a finished run into a skill, and route a new task to an existing skill. */
object Learner {
    data class Learned(
        val title: String,
        val pattern: String,
        val params: Map<String, String>,
        val readout: Boolean,
        val reusable: Boolean,
        val notes: List<Pair<String, String>>,
        val usage: Usage,
    )

    private const val LEARN_SYSTEM = """You turn a successful Android phone automation into a reusable skill.
Find the parts of the TASK that would change next time (names, messages, search terms, numbers, captions…) and make them {slots}.
Rules:
- pattern: the TASK rewritten with {slot} placeholders, same language and wording as the TASK. Slot names: short, lowercase, english (e.g. {contact}, {message}, {query}).
- params: for every slot, its exact value as it appears in this TASK (verbatim substring).
- If nothing would change (a fixed routine), return the TASK unchanged with no params.
- readout=true if the result reports information read from the screen that can differ next time (balances, notifications, weather…).
- reusable=false only if repeating it makes no sense.
- notes: up to 3 short English tips about WHERE things are in the app(s) used (e.g. "Edit profile: Profile tab → Edit profile button"). Only facts seen in the STEPS. Use the package name of the app."""

    private val learnTool = ToolSpec(
        "learn", "Save the generalized skill.",
        JSONObject(
            """
{"type":"object","properties":{
 "title":{"type":"string","description":"Short title in the task's language"},
 "pattern":{"type":"string"},
 "params":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"value":{"type":"string"}},"required":["name","value"]}},
 "readout":{"type":"boolean"},
 "reusable":{"type":"boolean"},
 "notes":{"type":"array","maxItems":3,"items":{"type":"object","properties":{"pkg":{"type":"string"},"note":{"type":"string"}},"required":["pkg","note"]}}
},"required":["title","pattern","params","readout","reusable"]}
"""
        ),
    )

    suspend fun learn(client: LlmClient, model: String, task: String, steps: List<MacroStep>, result: String, pkg: String): Learned? {
        val user = buildString {
            append("TASK: ").append(task).append('\n')
            append("FINAL APP: ").append(pkg).append('\n')
            append("STEPS:\n")
            steps.forEachIndexed { i, s ->
                append(i + 1).append(". ").append(s.type)
                s.label?.let { append(" target=\"").append(it.take(50)).append('"') }
                s.text?.let { append(" text=\"").append(it.take(80)).append('"') }
                s.dir?.let { append(" dir=").append(it) }
                append('\n')
            }
            append("RESULT: ").append(result.take(300))
        }
        val r = runCatching { client.callTool(model, LEARN_SYSTEM, user, null, learnTool, 600) }.getOrNull() ?: return null
        val o = r.input ?: return null
        val pa = o.optJSONArray("params") ?: JSONArray()
        val params = LinkedHashMap<String, String>()
        val nTask = Text.norm(task)
        for (i in 0 until pa.length()) {
            val p = pa.optJSONObject(i) ?: continue
            val name = p.optString("name").trim().trim('{', '}')
            val value = p.optString("value").trim()
            if (name.isNotEmpty() && value.isNotEmpty() && nTask.contains(Text.norm(value))) params[name] = value
        }
        var pattern = o.optString("pattern").ifBlank { task }
        // Pattern must still reproduce the task; otherwise fall back to a fixed routine.
        if (params.isNotEmpty() && Templates.match(pattern, task) == null) {
            pattern = Templates.templatize(task, params) ?: task
            if (Templates.match(pattern, task) == null) { pattern = task; params.clear() }
        }
        if (params.isEmpty()) pattern = task
        val na = o.optJSONArray("notes") ?: JSONArray()
        val notes = (0 until na.length()).mapNotNull { na.optJSONObject(it) }
            .map { it.optString("pkg").ifBlank { pkg } to it.optString("note") }
            .filter { it.second.isNotBlank() }
        return Learned(
            o.optString("title").ifBlank { task.take(40) }, pattern, params,
            o.optBoolean("readout", false), o.optBoolean("reusable", true), notes, r.usage,
        )
    }

    private const val ROUTE_SYSTEM = """Match the owner's phone TASK to one of their saved SKILLS, only if it has the same intent and steps (just different values).
Return skill_id null if none fits exactly. Extract each slot value verbatim from the TASK."""

    private val routeTool = ToolSpec(
        "route", "Pick a skill or null.",
        JSONObject(
            """
{"type":"object","properties":{
 "skill_id":{"type":["string","null"]},
 "params":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"value":{"type":"string"}},"required":["name","value"]}}
},"required":["skill_id"]}
"""
        ),
    )

    suspend fun route(client: LlmClient, model: String, task: String, candidates: List<Skill>): Triple<Skill, Map<String, String>, Usage>? {
        val user = buildString {
            append("TASK: ").append(task).append("\nSKILLS:\n")
            candidates.forEach { append(it.id).append(" | ").append(it.title).append(" | ").append(it.pattern).append('\n') }
        }
        val r = runCatching { client.callTool(model, ROUTE_SYSTEM, user, null, routeTool, 200) }.getOrNull() ?: return null
        val o = r.input ?: return null
        val id = o.optString("skill_id").takeIf { !o.isNull("skill_id") && it.isNotBlank() && it != "null" } ?: return null
        val skill = candidates.firstOrNull { it.id == id } ?: return null
        val pa = o.optJSONArray("params") ?: JSONArray()
        val params = LinkedHashMap<String, String>()
        for (i in 0 until pa.length()) {
            val p = pa.optJSONObject(i) ?: continue
            params[p.optString("name").trim().trim('{', '}')] = p.optString("value").trim()
        }
        if (!skill.params.all { !params[it].isNullOrBlank() }) return null
        return Triple(skill, params, r.usage)
    }

    fun newSkill(task: String, learned: Learned?, route: List<MacroStep>, result: String, pkg: String = ""): Skill {
        var params = learned?.params ?: emptyMap()
        var pattern = learned?.pattern ?: task
        val clean = route.filterIndexed { i, st ->
            !(i > 0 && st.type == "open_app" && route[i - 1].type == "open_app" && route[i - 1].text == st.text)
        }
        var steps = if (params.isEmpty()) clean else clean.map {
            if (it.type == "open_app" || it.type == "open_settings") it
            else it.copy(text = Templates.templatize(it.text, params), label = Templates.templatize(it.label, params))
        }
        // Every slot must drive at least one step; otherwise a replay would silently reuse the old value.
        if (params.isNotEmpty() && !params.keys.all { k -> steps.any { Templates.uses(it, k) } }) {
            params = emptyMap()
            pattern = task
            steps = clean
        }
        return Skill(
            id = UUID.randomUUID().toString().take(8),
            title = learned?.title ?: task.take(40),
            pattern = pattern,
            params = params.keys.toList(),
            example = task,
            steps = steps,
            doneTpl = Templates.templatize(result, params) ?: result,
            readout = learned?.readout ?: false,
            pkg = pkg,
        )
    }

    /**
     * Finds the element a saved step refers to. Order: the exact element as recorded → its label
     * (with {slots} filled) → its view id, which stays the same when the phone language or a counter changes.
     */
    fun locate(screen: Screen, step: MacroStep, params: Map<String, String>): UiNode? {
        val templated = Templates.isTemplated(step.label)
        if (!templated && step.sig != null) screen.find(step.sig)?.let { return it }
        val parts = step.sig?.split('|', limit = 3).orEmpty()
        val cls = parts.getOrNull(0).orEmpty()
        val label = Templates.fill(step.label, params)?.trim().orEmpty()
        val idOnly = label.startsWith("#")
        val viewId = parts.getOrNull(1).orEmpty().ifEmpty { if (idOnly) label.drop(1) else "" }
        val sameId = if (viewId.isEmpty()) emptyList() else screen.nodes.filter { it.viewId == viewId && (cls.isEmpty() || it.cls == cls) }
        if (label.isNotEmpty() && !idOnly) {
            val n = Text.norm(label)
            val normed = screen.nodes.map { it to Text.norm(it.label) }
            val cands = normed.filter { it.second == n }
                .ifEmpty { normed.filter { it.second.startsWith(n) } }
                .ifEmpty { if (n.length >= 2) normed.filter { it.second.contains(n) } else emptyList() }
                .map { it.first }
            (cands.firstOrNull { c -> sameId.any { it === c } }
                ?: cands.firstOrNull { it.clickable || it.editable }
                ?: cands.firstOrNull())?.let { return it }
            // The label is a value that changes every run: never settle for "whatever carries the same id"
            if (templated) return null
        }
        return sameId.singleOrNull()
    }
}
