package com.wakeel.agent.agent

import android.content.Context
import com.wakeel.agent.core.Text
import com.wakeel.agent.service.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One screen of an app as learned on this phone: its structural elements, not its content. */
class MapScreen(
    val key: String,
    var activity: String,
    var tab: String,
    var title: String,
    var titleSame: Int,
    var titleChanges: Int,
    val elements: LinkedHashMap<String, Int>,
    var visits: Int,
    var lastSeen: Long,
) {
    val name: String
        get() = when {
            tab.isNotBlank() -> tab
            title.isNotBlank() && titleSame >= 1 && titleChanges == 0 -> title
            else -> AppMapStore.prettyActivity(activity)
        }

    fun stableElements(max: Int): List<String> =
        elements.entries.filter { it.value * 2 >= visits }.sortedByDescending { it.value }.take(max).map { it.key }
}

class MapEdge(val from: String, val label: String, val to: String, var count: Int)

class AppMap(val pkg: String) {
    val screens = LinkedHashMap<String, MapScreen>()
    val edges = ArrayList<MapEdge>()
}

/**
 * Per-app maps of screens and the taps that lead between them, built for free while the agent works
 * (and, if the owner enables it, while they use the phone). Given to the model so it can plan a whole route at once.
 */
class AppMapStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "maps").apply { mkdirs() }
    private val cache = HashMap<String, AppMap>()
    private val dirty = HashSet<String>()
    private var lastFlush = 0L

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    companion object {
        private const val MAX_SCREENS = 40
        private const val MAX_ELEMENTS = 30
        private const val MAX_EDGES = 90
        const val ITEM = "(item)"

        fun prettyActivity(a: String): String =
            a.removeSuffix("Activity").removeSuffix("Fragment").ifBlank { "Screen" }

        fun keyOf(s: Screen): String {
            val structural = s.structural()
            val ids = structural.asSequence().map { it.viewId }.filter { it.isNotEmpty() }.distinct().sorted().take(24).toList()
            val basis = if (ids.size >= 3) ids.joinToString(",")
            else structural.asSequence().map { Text.norm(it.label) }.filter { okLabel(it) }.distinct().sorted().take(16).joinToString(",")
            return s.activity + "#" + Integer.toHexString((basis + "|" + Text.norm(tabOf(s))).hashCode())
        }

        fun tabOf(s: Screen): String =
            s.nodes.firstOrNull { it.selected && !it.inList && it.label.isNotBlank() && it.label.length <= 24 }?.label?.trim().orEmpty()

        private fun titleOf(s: Screen): String =
            s.nodes.firstOrNull {
                it.role == "txt" && !it.clickable && !it.inList && it.bounds.centerY() < s.height * 0.14f && it.label.length in 2..28
            }?.label?.trim().orEmpty()

        fun okLabel(l: String): Boolean = l.length in 2..28 && l.count { it.isDigit() } < 3

        fun clean(l: String): String = l.replace('\n', ' ').replace('"', '\'').trim()
    }

    @Synchronized
    private fun map(pkg: String): AppMap = cache.getOrPut(pkg) { load(pkg) }

    private fun file(pkg: String) = File(dir, pkg.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    private fun load(pkg: String): AppMap {
        val m = AppMap(pkg)
        runCatching {
            val o = JSONObject(file(pkg).readText())
            val sa = o.optJSONArray("s") ?: JSONArray()
            for (i in 0 until sa.length()) {
                val s = sa.optJSONObject(i) ?: continue
                val els = LinkedHashMap<String, Int>()
                s.optJSONObject("e")?.let { e -> e.keys().forEach { k -> els[k] = e.optInt(k) } }
                m.screens[s.optString("k")] = MapScreen(
                    s.optString("k"), s.optString("a"), s.optString("tb"), s.optString("t"),
                    s.optInt("ts"), s.optInt("tc"), els, s.optInt("v"), s.optLong("l"),
                )
            }
            val ea = o.optJSONArray("e") ?: JSONArray()
            for (i in 0 until ea.length()) {
                val e = ea.optJSONObject(i) ?: continue
                m.edges += MapEdge(e.optString("f"), e.optString("l"), e.optString("t"), e.optInt("c", 1))
            }
        }
        return m
    }

    /** Records the screen and returns its key, or null if there is nothing structural to learn. */
    @Synchronized
    fun observe(s: Screen): String? {
        if (s.pkg.isBlank() || s.nodes.size < 2 || s.hasPassword) return null
        val m = map(s.pkg)
        val key = keyOf(s)
        val now = System.currentTimeMillis()
        val title = titleOf(s)
        val sc = m.screens.getOrPut(key) { MapScreen(key, s.activity, tabOf(s), title, 0, 0, LinkedHashMap(), 0, now) }
        sc.visits++
        sc.lastSeen = now
        if (sc.visits > 1) {
            if (title == sc.title) sc.titleSame++ else { sc.title = title; sc.titleChanges++ }
        }
        for (n in s.structural()) {
            val l = clean(n.label)
            if (!okLabel(l)) continue
            val k = if (n.editable) "✎$l" else l
            sc.elements[k] = (sc.elements[k] ?: 0) + 1
        }
        if (sc.elements.size > MAX_ELEMENTS) {
            val keep = sc.elements.entries.sortedByDescending { it.value }.take(MAX_ELEMENTS).map { it.key }.toSet()
            sc.elements.keys.retainAll(keep)
        }
        if (m.screens.size > MAX_SCREENS) {
            val drop = m.screens.values.sortedWith(compareBy<MapScreen> { it.visits }.thenBy { it.lastSeen }).first().key
            m.screens.remove(drop)
            m.edges.removeAll { it.from == drop || it.to == drop }
        }
        dirty += s.pkg
        return key
    }

    /** Records that tapping [label] on screen [from] led to screen [to]. */
    @Synchronized
    fun edge(pkg: String, from: String, label: String, to: String) {
        if (from == to || pkg.isBlank()) return
        val l = clean(label).let { if (okLabel(it)) it else ITEM }
        val m = map(pkg)
        val e = m.edges.firstOrNull { it.from == from && it.to == to && it.label == l }
        if (e != null) e.count++ else m.edges += MapEdge(from, l, to, 1)
        if (m.edges.size > MAX_EDGES) m.edges.sortedBy { it.count }.take(m.edges.size - MAX_EDGES).forEach { m.edges.remove(it) }
        dirty += pkg
    }

    /** Compact text for the model. Empty when nothing is known. */
    @Synchronized
    fun describe(pkg: String, maxChars: Int = 900): String {
        val m = map(pkg)
        if (m.screens.isEmpty()) return ""
        val byName = LinkedHashMap<String, LinkedHashSet<String>>()
        for (s in m.screens.values.sortedByDescending { it.visits }.take(16)) {
            val els = s.stableElements(12)
            if (els.isEmpty()) continue
            byName.getOrPut(s.name) { LinkedHashSet() }.addAll(els)
        }
        val sb = StringBuilder()
        for ((name, els) in byName) {
            val line = "[" + name + "] " + els.take(14).joinToString(", ") + "\n"
            if (sb.length + line.length > maxChars * 2 / 3) break
            sb.append(line)
        }
        val paths = LinkedHashSet<String>()
        for (e in m.edges.sortedByDescending { it.count }) {
            val a = m.screens[e.from]?.name ?: continue
            val b = m.screens[e.to]?.name ?: continue
            if (a == b) continue
            paths += "$a -\"${e.label}\"→ $b"
            if (paths.size >= 16) break
        }
        if (paths.isNotEmpty()) {
            sb.append("PATHS: ")
            for (p in paths) {
                if (sb.length + p.length > maxChars) break
                sb.append(p).append("; ")
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    @Synchronized
    fun screens(pkg: String): List<MapScreen> = map(pkg).screens.values.sortedByDescending { it.visits }

    @Synchronized
    fun screenCount(pkg: String): Int = map(pkg).screens.size

    @Synchronized
    fun pathCount(pkg: String): Int = map(pkg).edges.size

    /** Packages that have a saved map. */
    fun knownPackages(): Set<String> {
        val onDisk = dir.list()?.map { it.removeSuffix(".json") }?.toSet() ?: emptySet()
        return synchronized(this) { onDisk + cache.filter { it.value.screens.isNotEmpty() }.keys }
    }

    @Synchronized
    fun totalScreens(): Int = knownPackages().sumOf { screenCount(it) }

    @Synchronized
    fun clear(pkg: String) {
        cache.remove(pkg)
        dirty.remove(pkg)
        runCatching { file(pkg).delete() }
        _version.value++
    }

    @Synchronized
    fun clearAll() {
        cache.clear()
        dirty.clear()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        _version.value++
    }

    @Synchronized
    fun flush(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (dirty.isEmpty() || (!force && now - lastFlush < 20_000)) return
        lastFlush = now
        for (pkg in dirty) {
            val m = cache[pkg] ?: continue
            val o = JSONObject()
            val sa = JSONArray()
            for (s in m.screens.values) {
                val e = JSONObject()
                s.elements.forEach { (k, v) -> e.put(k, v) }
                sa.put(
                    JSONObject().put("k", s.key).put("a", s.activity).put("tb", s.tab).put("t", s.title)
                        .put("ts", s.titleSame).put("tc", s.titleChanges).put("v", s.visits).put("l", s.lastSeen).put("e", e)
                )
            }
            val ea = JSONArray()
            for (e in m.edges) ea.put(JSONObject().put("f", e.from).put("l", e.label).put("t", e.to).put("c", e.count))
            o.put("s", sa).put("e", ea)
            runCatching { file(pkg).writeText(o.toString()) }
        }
        dirty.clear()
        _version.value++
    }
}

/** How well the agent knows an app (0–100), from built-in knowledge and what it learned on this phone. */
object Mastery {
    fun score(hasGuide: Boolean, screens: Int, skills: Int, notes: Int): Int =
        ((if (hasGuide) 35 else 0) + minOf(screens, 10) * 3 + minOf(skills, 5) * 5 + minOf(notes, 5) * 2).coerceAtMost(100)

    fun level(score: Int): String = when {
        score >= 75 -> "خبير"
        score >= 45 -> "متمكّن"
        score >= 20 -> "متوسط"
        score > 0 -> "مبتدئ"
        else -> "جديد"
    }
}
