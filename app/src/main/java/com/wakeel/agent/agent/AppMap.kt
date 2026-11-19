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

/** A tap that leads from one screen to another. [id] is the tapped element's view id (the same in every phone language). */
class MapEdge(val from: String, val label: String, val to: String, var count: Int, var id: String = "")

class AppMap(val pkg: String) {
    val screens = LinkedHashMap<String, MapScreen>()
    val edges = ArrayList<MapEdge>()

    /**
     * Screens the app has shown right after a fresh start. More than one is kept: the same first page can
     * be recorded under slightly different keys (one more row visible, a banner on top), and a path learned
     * from one of them must still be found when the latest start produced another.
     */
    val roots = LinkedHashSet<String>()

    /** The most recent first page. */
    var root: String
        get() = roots.lastOrNull().orEmpty()
        set(v) {
            if (v.isEmpty()) return
            roots.remove(v)
            roots.add(v)
            while (roots.size > 4) roots.remove(roots.first())
        }

    /**
     * The shortest known tap sequence from the app's first screen to the page called [query]: a tab,
     * a screen title, or the label of the button that opens it. Empty = the first screen itself.
     * Null when the map does not know such a page. Pure, so it is unit-tested on CI.
     */
    fun findPath(query: String): List<MapEdge>? {
        val q = Text.norm(query)
        val starts = roots.filter { it in screens }
        if (q.length < 2 || starts.isEmpty()) return null
        if (starts.any { Text.norm(screens[it]?.name.orEmpty()) == q }) return emptyList()

        fun score(e: MapEdge): Int {
            val label = Text.norm(e.label)
            val target = screens[e.to]
            val name = Text.norm(target?.name.orEmpty())
            return when {
                label == q || name == q || Text.norm(target?.title.orEmpty()) == q -> 2
                q.length >= 3 && label.contains(q) -> 1
                else -> 0
            }
        }

        val usable = edges.filter { it.label != AppMapStore.ITEM && it.from in screens && it.to in screens }.sortedByDescending { it.count }
        val reachedBy = HashMap<String, MapEdge>()
        val depth = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        // The latest first page is tried first
        for (s in starts.reversed()) {
            depth[s] = 0
            queue += s
        }
        var partial: MapEdge? = null

        fun pathTo(e: MapEdge): List<MapEdge>? {
            val out = ArrayList<MapEdge>()
            out += e
            var at = e.from
            while (at !in starts) {
                val p = reachedBy[at] ?: return null
                out += p
                at = p.from
                if (out.size > 8) return null
            }
            return out.reversed()
        }

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val d = depth[cur] ?: 0
            if (d >= 5) continue
            for (e in usable) {
                if (e.from != cur) continue
                when (score(e)) {
                    2 -> return pathTo(e)
                    1 -> if (partial == null) partial = e
                }
                if (e.to !in depth) {
                    depth[e.to] = d + 1
                    reachedBy[e.to] = e
                    queue += e.to
                }
            }
        }
        return partial?.let { pathTo(it) }
    }
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

    // The next screen seen in this app is its first page (set right after a fresh launch).
    private var rootPkg = ""
    private var rootUntil = 0L

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    companion object {
        private const val MAX_SCREENS = 40
        private const val MAX_ELEMENTS = 30
        private const val MAX_ICONS = 6
        private const val MAX_EDGES = 90
        const val ITEM = "(item)"

        fun prettyActivity(a: String): String =
            a.removeSuffix("Activity").removeSuffix("Fragment").ifBlank { "Screen" }

        fun keyOf(s: Screen): String {
            val structural = s.structural()
            val ids = structural.asSequence().map { it.viewId }.filter { it.isNotEmpty() }.distinct().sorted().take(24).toList()
            val basis = if (ids.size >= 3) ids.joinToString(",")
            else structural.asSequence().map { Text.norm(it.title) }.filter { okLabel(it) }.distinct().take(8).joinToString(",")
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
                m.edges += MapEdge(e.optString("f"), e.optString("l"), e.optString("t"), e.optInt("c", 1), e.optString("i"))
            }
            val rs = o.optJSONArray("rs")
            if (rs != null) for (i in 0 until rs.length()) m.root = rs.optString(i)
            else m.root = o.optString("r")
        }
        return m
    }

    /** Call right after starting [pkg] fresh: the next screen recorded for it is marked as its first page. */
    @Synchronized
    fun expectRoot(pkg: String) {
        rootPkg = pkg
        rootUntil = System.currentTimeMillis() + 20_000
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
        if (rootPkg == s.pkg) {
            if (now <= rootUntil) m.root = key
            rootPkg = ""
        }
        for (n in s.structural()) {
            val l = clean(n.title)   // "Battery · 100%" is remembered as "Battery": the detail changes, the name does not
            if (!okLabel(l)) continue
            val k = if (n.editable) "✎$l" else l
            sc.elements[k] = (sc.elements[k] ?: 0) + 1
        }
        // Icons without any text are remembered by their view id, so a plan can still name them (find:"#id")
        var icons = 0
        for (n in s.nodes) {
            if (icons >= MAX_ICONS) break
            if (!n.clickable || n.inList || n.password || n.editable || n.label.isNotBlank() || n.viewId.length !in 3..40) continue
            val k = "#" + n.viewId
            sc.elements[k] = (sc.elements[k] ?: 0) + 1
            icons++
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

    /** Records that tapping [label] (view id [viewId]) on screen [from] led to screen [to]. */
    @Synchronized
    fun edge(pkg: String, from: String, label: String, to: String, viewId: String = "") {
        if (from == to || pkg.isBlank()) return
        val c = clean(label)
        val l = when {
            c.startsWith("#") && c.length in 4..41 -> c
            okLabel(c) -> c
            c.isBlank() && viewId.length in 3..40 -> "#$viewId"
            else -> ITEM
        }
        val m = map(pkg)
        val e = m.edges.firstOrNull { it.from == from && it.to == to && it.label == l }
        if (e != null) {
            e.count++
            if (viewId.isNotEmpty()) e.id = viewId
        } else {
            m.edges += MapEdge(from, l, to, 1, viewId)
        }
        if (m.edges.size > MAX_EDGES) m.edges.sortedBy { it.count }.take(m.edges.size - MAX_EDGES).forEach { m.edges.remove(it) }
        dirty += pkg
    }

    /** Taps that lead from the app's first page to the page called [query], or null if the map does not know it. */
    @Synchronized
    fun findPath(pkg: String, query: String): List<MapEdge>? = map(pkg).findPath(query)?.toList()

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
            for (e in m.edges) {
                val eo = JSONObject().put("f", e.from).put("l", e.label).put("t", e.to).put("c", e.count)
                if (e.id.isNotEmpty()) eo.put("i", e.id)
                ea.put(eo)
            }
            o.put("s", sa).put("e", ea).put("rs", JSONArray(m.roots.toList()))
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
