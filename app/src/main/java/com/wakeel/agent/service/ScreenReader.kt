package com.wakeel.agent.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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
    val info: AccessibilityNodeInfo,
) {
    /** Stable identity across screen reads (used for batching and saved routes). */
    val signature: String get() = "$cls|$viewId|${label.take(60)}"
}

class Screen(val pkg: String, val nodes: List<UiNode>, val width: Int, val height: Int) {
    val hasPassword = nodes.any { it.password }
    val hash: Int = (pkg + "\n" + nodes.joinToString("\n") { it.signature + "@" + it.bounds.flattenToString() }).hashCode()

    fun byId(id: Int): UiNode? = nodes.getOrNull(id)

    fun find(sig: String, near: Rect? = null): UiNode? {
        val c = nodes.filter { it.signature == sig }
        if (c.isEmpty()) return null
        if (near == null) return c.first()
        return c.minBy { abs(it.bounds.centerX() - near.centerX()) + abs(it.bounds.centerY() - near.centerY()) }
    }

    /** Token-lean representation: `id|role|label|flags|@xNyM`. */
    fun compact(): String = buildString {
        for (n in nodes) {
            append(n.id).append('|').append(n.role).append('|')
            append(n.label.replace('\n', ' ').replace('|', '/'))
            append('|')
            if (n.clickable) append('c')
            if (n.longClickable) append('L')
            if (n.editable) append('e')
            if (n.scrollable) append('s')
            if (n.checked) append('x')
            if (n.password) append('p')
            val gx = if (width > 0) (n.bounds.exactCenterX() * 10 / width).toInt().coerceIn(0, 9) else 0
            val gy = if (height > 0) (n.bounds.exactCenterY() * 10 / height).toInt().coerceIn(0, 9) else 0
            append("|@x").append(gx).append('y').append(gy).append('\n')
        }
    }
}

object ScreenReader {
    private const val MAX_NODES = 160
    private const val MAX_LABEL = 50

    fun read(svc: AccessibilityService, w: Int, h: Int): Screen {
        val root = svc.rootInActiveWindow ?: return Screen("", emptyList(), w, h)
        val pkg = root.packageName?.toString() ?: ""
        val out = ArrayList<UiNode>()
        walk(root, out, w, h, false, 0)
        return Screen(pkg, out, w, h)
    }

    private fun ownText(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return "(password)"
        val t = n.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        if (d.isNotEmpty()) return d
        return n.hintText?.toString()?.trim().orEmpty()
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableList<UiNode>, w: Int, h: Int, underLabeled: Boolean, depth: Int) {
        if (out.size >= MAX_NODES || depth > 45) return
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
                label = label.take(MAX_LABEL),
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
                info = n,
            )
        }
        val childUnder = underLabeled || merged || (interactive && own.isNotEmpty() && !n.isScrollable)
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, out, w, h, childUnder, depth + 1)
        }
    }

    private fun collectText(n: AccessibilityNodeInfo, max: Int): String {
        val parts = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        var visited = 0
        while (queue.isNotEmpty() && parts.size < max && visited < 40) {
            val c = queue.removeFirst()
            visited++
            if (!c.isVisibleToUser) continue
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
