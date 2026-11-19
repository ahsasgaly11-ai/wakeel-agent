package com.wakeel.agent.agent

import android.content.Context
import android.content.Intent
import com.wakeel.agent.core.Text
import com.wakeel.agent.service.AgentAccessibilityService

/** Installed launchable apps + Arabic/English name matching. */
class AppIndex(private val ctx: Context) {
    data class App(val label: String, val pkg: String, val norm: String)

    @Volatile
    private var cache: List<App>? = null
    private var cacheAt = 0L

    @Volatile
    private var launchers: Set<String>? = null

    fun all(): List<App> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - cacheAt < 60_000) return it }
        val pm = ctx.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map {
                val l = it.loadLabel(pm).toString()
                App(l, it.activityInfo.packageName, Text.norm(l))
            }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
        cache = list
        cacheAt = now
        return list
    }

    /**
     * Home-screen apps. The Settings app also answers the "home" intent (a low-priority fallback shown
     * before the phone is unlocked) — it is not a launcher and must stay a normal app for the agent.
     */
    fun launcherPackages(): Set<String> = launchers ?: ctx.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .filter { it.priority >= 0 && it.activityInfo.packageName != "com.android.settings" }
        .map { it.activityInfo.packageName }.toSet().also { launchers = it }

    private fun matchKeys(keys: List<String>): App? =
        all().firstOrNull { a -> keys.any { k -> a.norm.contains(k) || a.pkg.contains(k) } }

    /** Strict match — used by the local router so "واتساب وأرسل…" never matches wrongly. */
    fun exact(q: String): App? {
        val n = Text.norm(q).removePrefix("تطبيق").trim()
        if (n.isEmpty()) return null
        all().firstOrNull { it.norm == n }?.let { return it }
        ALIASES[n]?.let { return matchKeys(it) }
        return null
    }

    /** Lenient match — used when the model asks to open an app by name. */
    fun fuzzy(q: String): App? {
        exact(q)?.let { return it }
        val n = Text.norm(q).removePrefix("تطبيق").trim()
        if (n.length < 2) return null
        all().firstOrNull { it.pkg == q.trim() }?.let { return it }
        all().firstOrNull { it.norm.startsWith(n) }?.let { return it }
        all().firstOrNull { it.norm.contains(n) }?.let { return it }
        ALIASES.entries.firstOrNull { n.contains(it.key) }?.let { e -> matchKeys(e.value)?.let { return it } }
        return all().firstOrNull { it.norm.length >= 3 && n.contains(it.norm) }
    }

    fun byPackage(pkg: String): App? = all().firstOrNull { it.pkg == pkg }

    /** Apps the task talks about (by name or Arabic alias) — their knowledge is given to the model up front. */
    fun mentioned(task: String): List<App> {
        val n = " " + Text.norm(task) + " "
        val out = LinkedHashSet<App>()
        for ((alias, keys) in ALIASES) {
            if (alias in MENTION_IGNORE) continue
            val hit = if (alias.length >= 4) n.contains(alias) else n.contains(" $alias ")
            if (hit) matchKeys(keys)?.let { out += it }
        }
        for (a in all()) if (a.norm.length >= 3 && n.contains(" ${a.norm} ")) out += a
        return out.take(2)
    }

    /** Arabic category for display: built-in knowledge first, then what the app declares to Android. */
    fun categoryOf(pkg: String): String {
        KnowledgeBase.guideFor(pkg)?.let { return it.category }
        val c = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).category }.getOrDefault(-1)
        return when (c) {
            android.content.pm.ApplicationInfo.CATEGORY_GAME -> "ألعاب"
            android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "صوت وموسيقى"
            android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "فيديو وترفيه"
            android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "صور"
            android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "تواصل اجتماعي"
            android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "أخبار"
            android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "خرائط وتنقل"
            android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "إنتاجية"
            else -> "أخرى"
        }
    }

    /**
     * Brings an app to the front. [fresh] restarts it on its first page, so a saved route
     * (or a plan built from the app's guide) always begins from the same place.
     */
    fun launch(pkg: String, fresh: Boolean = false): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (fresh) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return runCatching { (AgentAccessibilityService.instance ?: ctx).startActivity(i); true }.getOrDefault(false)
    }

    companion object {
        private val RAW = mapOf(
            listOf("واتساب", "واتس", "وتساب", "الواتس") to listOf("whatsapp"),
            listOf("يوتيوب", "يوتوب", "اليوتيوب") to listOf("youtube"),
            listOf("انستقرام", "انستغرام", "انستجرام", "انستا") to listOf("instagram"),
            listOf("سناب", "سناب شات", "السناب") to listOf("snapchat"),
            listOf("تويتر", "اكس", "منصه اكس") to listOf("com.twitter", "twitter"),
            listOf("تيك توك", "تيكتوك") to listOf("tiktok", "musically", "trill"),
            listOf("تيليجرام", "تلجرام", "تليجرام", "تلقرام") to listOf("telegram"),
            listOf("فيسبوك", "فيس بوك") to listOf("facebook.katana", "facebook"),
            listOf("ماسنجر") to listOf("orca", "messenger"),
            listOf("كروم", "جوجل كروم", "المتصفح") to listOf("chrome", "browser"),
            listOf("جوجل", "قوقل") to listOf("googlequicksearchbox"),
            listOf("خرائط", "الخرائط", "خرائط جوجل", "قوقل ماب") to listOf("apps.maps", "maps"),
            listOf("جيميل", "البريد", "الايميل") to listOf("android.gm", "gmail", "email"),
            listOf("الكاميرا", "كاميرا") to listOf("camera"),
            listOf("الاعدادات", "اعدادات", "الضبط") to listOf("com.android.settings", "settings"),
            listOf("الرسائل", "رسائل") to listOf("messaging", "mms", "messages"),
            listOf("الهاتف", "الاتصال", "المكالمات") to listOf("dialer"),
            listOf("جهات الاتصال", "الاسماء") to listOf("contacts"),
            listOf("الصور", "المعرض", "الاستديو", "الاستوديو") to listOf("apps.photos", "gallery"),
            listOf("الساعه", "المنبه") to listOf("deskclock", "clock"),
            listOf("التقويم") to listOf("calendar"),
            listOf("الحاسبه", "الاله الحاسبه") to listOf("calculator"),
            listOf("المتجر", "متجر جوجل", "متجر بلاي", "بلاي ستور", "جوجل بلاي") to listOf("vending"),
            listOf("سبوتيفاي") to listOf("spotify"),
            listOf("نتفليكس") to listOf("netflix"),
            listOf("شات جي بي تي", "تشات جي بي تي") to listOf("chatgpt", "openai"),
            listOf("كلود") to listOf("anthropic", "claude"),
            listOf("زوم") to listOf("zoom"),
            listOf("تيمز") to listOf("teams"),
            listOf("اوبر") to listOf("uber"),
            listOf("كريم") to listOf("careem"),
            listOf("طلبات") to listOf("talabat"),
            listOf("سنونو") to listOf("snoonu"),
            listOf("مطراش") to listOf("metrash"),
            listOf("الملفات", "ملفاتي", "مدير الملفات") to listOf("documentsui", "files", "filemanager", "myfiles"),
            listOf("درايف", "جوجل درايف") to listOf("apps.docs", "drive"),
            listOf("يوتيوب ميوزك") to listOf("youtube.music"),
            listOf("المسجل", "مسجل الصوت") to listOf("recorder"),
            listOf("الملاحظات", "ملاحظات") to listOf("keep", "notes", "memo"),
        )

        private val MENTION_IGNORE = setOf("الهاتف", "الاتصال", "المتجر", "الساعه", "جوجل", "قوقل").map { Text.norm(it) }.toSet()

        val ALIASES: Map<String, List<String>> = buildMap {
            RAW.forEach { (names, keys) -> names.forEach { put(Text.norm(it), keys) } }
        }
    }
}
