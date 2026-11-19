package com.wakeel.agent.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wakeel.agent.core.Text
import kotlin.math.abs

class UiNode(
    val id: Int,
    val label: String,
    val role: String,
    val viewId: String,
    val cls: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    /** Inside a dynamic list (RecyclerView/ListView…): content, not app structure. */
    val inList: Boolean,
    val info: AccessibilityNodeInfo,
) {
    /** Stable identity across screen reads (used for batching and saved routes). */
    val signature: String get() = "$cls|$viewId|${label.take(60)}"

    /** What the element is called. A row shows "Title · detail · detail"; the details change, the title does not. */
    val title: String get() = label.substringBefore(" · ").trim()
}

class Screen(val pkg: String, val activity: String, val nodes: List<UiNode>, val width: Int, val height: Int) {
    val hasPassword = nodes.any { it.password }
    val hash: Int = (pkg + "\n" + nodes.joinToString("\n") {
        it.signature + "@" + it.bounds.flattenToString() + (if (it.checked) "x" else "") + (if (it.selected) "*" else "")
    }).hashCode()

    fun byId(id: Int): UiNode? = nodes.getOrNull(id)

    fun find(sig: String, near: Rect? = null): UiNode? {
        val c = nodes.filter { it.signature == sig }
        if (c.isEmpty()) return null
        if (near == null) return c.first()
        return c.minBy { abs(it.bounds.centerX() - near.centerX()) + abs(it.bounds.centerY() - near.centerY()) }
    }

    /** Finds an element by its visible label (or view id) — used for steps planned before the screen was seen. */
    fun match(query: String, wantEditable: Boolean = false): UiNode? {
        if (query.contains('|')) {
            // alternatives, e.g. "تعديل الملف الشخصي|Edit profile"
            for (alt in query.split('|')) if (alt.isNotBlank()) matchOne(alt, wantEditable)?.let { return it }
            return null
        }
        return matchOne(query, wantEditable)
    }

    private fun matchOne(query: String, wantEditable: Boolean): UiNode? {
        val raw = query.trim().trimStart('#')
        val q = Text.norm(raw)
        if (q.isEmpty()) return null
        val pool = if (wantEditable) nodes.filter { it.editable } else nodes
        if (pool.isEmpty()) return null
        fun best(c: List<UiNode>): UiNode? =
            c.sortedWith(compareBy<UiNode> { if (it.clickable || it.editable) 0 else 1 }.thenBy { it.label.length }).firstOrNull()

        val labels = pool.map { it to Text.norm(it.label) }
        best(labels.filter { it.second == q }.map { it.first })?.let { return it }
        best(pool.filter { it.viewId.isNotEmpty() && it.viewId.equals(raw, ignoreCase = true) })?.let { return it }
        best(labels.filter { it.second.startsWith(q) }.map { it.first })?.let { return it }
        if (q.length >= 2) best(labels.filter { it.second.contains(q) }.map { it.first })?.let { return it }
        best(labels.filter { it.second.length >= 4 && q.contains(it.second) }.map { it.first })?.let { return it }
        if (q.length >= 4) best(pool.filter { it.viewId.isNotEmpty() && Text.norm(it.viewId.replace('_', ' ')).contains(q) })?.let { return it }
        return null
    }

    /** Smallest labelled element under a screen point (for checking coordinate taps). */
    fun at(x: Float, y: Float): UiNode? = nodes
        .filter { it.label.isNotBlank() && it.bounds.contains(x.toInt(), y.toInt()) }
        .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }

    /** Elements that describe the app's structure (toolbars, tabs, buttons, fields) rather than its content. */
    fun structural(): List<UiNode> = nodes.filter { (it.clickable || it.editable) && !it.inList && !it.password && it.label.isNotBlank() }

    /** Token-lean representation: `id|role|label|flags|@xNyM`. */
    fun compact(): String = buildString {
        for (n in nodes) {
            append(n.id).append('|').append(n.role).append('|')
            append(
                if (n.editable) n.label.replace("\n", "\\n").replace('|', '/')
                else n.label.replace('\n', ' ').replace('|', '/')
            )
            append('|')
            if (n.clickable) append('c')
            if (n.longClickable) append('L')
            if (n.editable) append('e')
            if (n.scrollable) append('s')
            if (n.checked) append('x')
            if (n.selected) append('*')
            if (n.password) append('p')
            val gx = if (width > 0) (n.bounds.exactCenterX() * 10 / width).toInt().coerceIn(0, 9) else 0
            val gy = if (height > 0) (n.bounds.exactCenterY() * 10 / height).toInt().coerceIn(0, 9) else 0
            append("|@x").append(gx).append('y').append(gy).append('\n')
        }
    }
}

object ScreenReader {
    private const val MAX_NODES = 160

    /** A dense grid (a gallery, a file list) may fill the page; the bars above and below it must still be read. */
    private const val MAX_LIST_NODES = 110
    private const val MAX_LABEL = 50

    /**
     * The app window to read. Normally the active one; while a page is still starting, Android may not
     * report an active window yet, so the front-most app window is used instead.
     */
    fun root(svc: AccessibilityService): AccessibilityNodeInfo? {
        svc.rootInActiveWindow?.let { return it }
        val apps = runCatching { svc.windows }.getOrNull().orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return runCatching { (apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: apps.firstOrNull())?.root }.getOrNull()
    }

    fun read(svc: AccessibilityService, w: Int, h: Int, activityFor: (String) -> String): Screen {
        val root = root(svc) ?: return Screen("", "", emptyList(), w, h)
        val pkg = root.packageName?.toString() ?: ""
        val out = ArrayList<UiNode>()
        walk(root, out, w, h, false, false, 0, null)
        return Screen(pkg, activityFor(pkg), out, w, h)
    }

    /**
     * The label the agent shows for [n] on screen: its own text, or the text of what it contains.
     * [evenIfHidden] also reads children that are no longer visible — the element was just tapped
     * and its page may already be closing.
     */
    fun labelOf(n: AccessibilityNodeInfo, evenIfHidden: Boolean = false): String {
        val own = ownText(n)
        return (if (own.isEmpty() && !n.isScrollable) collectText(n, 3, evenIfHidden) else own).take(MAX_LABEL)
    }

    private fun ownText(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return "(password)"
        val t = n.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        if (d.isNotEmpty()) return d
        return n.hintText?.toString()?.trim().orEmpty()
    }

    private fun isDynamicList(n: AccessibilityNodeInfo): Boolean {
        if (!n.isScrollable) return false
        val c = n.className?.toString().orEmpty()
        // Plain scroll containers and pagers hold static structure; adapter-backed lists hold content.
        return !(c.endsWith("ScrollView") || c.contains("Pager"))
    }

    private fun walk(
        n: AccessibilityNodeInfo, out: MutableList<UiNode>, w: Int, h: Int,
        underLabeled: Boolean, inList: Boolean, depth: Int, listRoom: IntArray?,
    ) {
        if (out.size >= MAX_NODES || depth > 45) return
        if (listRoom != null && listRoom[0] <= 0) return
        if (!n.isVisibleToUser) return
        val b = Rect()
        n.getBoundsInScreen(b)
        if (b.width() <= 1 || b.height() <= 1 || b.right <= 0 || b.bottom <= 0 || b.left >= w || b.top >= h) return

        val own = ownText(n)
        val interactive = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable
        var label = own
        var merged = false
        if (interactive && label.isEmpty() && !n.isScrollable) {
            label = collectText(n, 3)
            merged = label.isNotEmpty()
        }
        if (interactive || (own.isNotEmpty() && !underLabeled)) {
            out += UiNode(
                id = out.size,
                label = if (n.isEditable && !n.isPassword) label.take(400) else label.take(MAX_LABEL),
                role = role(n),
                viewId = n.viewIdResourceName?.substringAfter(":id/").orEmpty(),
                cls = n.className?.toString()?.substringAfterLast('.').orEmpty(),
                bounds = b,
                clickable = n.isClickable,
                longClickable = n.isLongClickable,
                editable = n.isEditable,
                scrollable = n.isScrollable,
                password = n.isPassword,
                checked = n.isChecked,
                selected = n.isSelected,
                inList = inList,
                info = n,
            )
            listRoom?.let { it[0]-- }
        }
        val childUnder = underLabeled || merged || (interactive && own.isNotEmpty() && !n.isScrollable)
        val dynamic = isDynamicList(n)
        val childInList = inList || dynamic
        val childRoom = listRoom ?: if (dynamic) intArrayOf(MAX_LIST_NODES) else null
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, out, w, h, childUnder, childInList, depth + 1, childRoom)
        }
    }

    private fun collectText(n: AccessibilityNodeInfo, max: Int, evenIfHidden: Boolean = false): String {
        val parts = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        var visited = 0
        while (queue.isNotEmpty() && parts.size < max && visited < 40) {
            val c = queue.removeFirst()
            visited++
            if (!evenIfHidden && !c.isVisibleToUser) continue
            val t = ownText(c)
            if (t.isNotEmpty() && t !in parts) parts += t
            for (i in 0 until c.childCount) c.getChild(i)?.let(queue::add)
        }
        return parts.joinToString(" · ")
    }

    private fun role(n: AccessibilityNodeInfo): String {
        val c = n.className?.toString()?.substringAfterLast('.').orEmpty()
        return when {
            n.isEditable || c.contains("EditText") -> "edit"
            c.contains("Switch") || c.contains("Toggle") -> "sw"
            n.isCheckable || c.contains("CheckBox") || c.contains("Radio") -> "chk"
            c == "ImageButton" -> "ibtn"
            c.contains("Button") -> "btn"
            n.isScrollable -> "list"
            c.contains("Image") -> if (n.isClickable) "ibtn" else "img"
            c.contains("Text") -> if (n.isClickable) "btn" else "txt"
            n.isClickable -> "btn"
            else -> "view"
        }
    }
}
