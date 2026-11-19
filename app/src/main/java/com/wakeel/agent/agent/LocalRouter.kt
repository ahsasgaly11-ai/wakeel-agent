package com.wakeel.agent.agent

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import com.wakeel.agent.core.Text
import com.wakeel.agent.service.AgentAccessibilityService
import java.util.Locale

/**
 * Handles common commands directly with Android intents — zero API calls.
 * Anything it can't fully handle goes to the model (optionally after opening the app first).
 */
class LocalRouter(private val ctx: Context, private val apps: AppIndex) {

    sealed interface Result {
        data class Handled(val message: String) : Result
        data class Opened(val app: String, val rest: String) : Result
        data object None : Result
    }

    fun route(task: String, svc: AgentAccessibilityService): Result {
        val t = Text.norm(task).trimEnd('.', '!', '؟', '?')
        val words = t.split(' ').filter { it.isNotBlank() }

        // Simple global navigation
        when (t) {
            in NAV_BACK -> { svc.global(AccessibilityService.GLOBAL_ACTION_BACK); return Result.Handled("تم الرجوع") }
            in NAV_HOME -> { svc.global(AccessibilityService.GLOBAL_ACTION_HOME); return Result.Handled("تم الذهاب للشاشة الرئيسية") }
            in NAV_NOTIF -> { svc.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); return Result.Handled("تم فتح الإشعارات") }
            in NAV_RECENTS -> { svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS); return Result.Handled("تم عرض التطبيقات الأخيرة") }
            in NAV_SHOT -> { svc.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT); return Result.Handled("تم أخذ لقطة شاشة") }
            in NAV_LOCK -> { svc.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); return Result.Handled("تم قفل الشاشة") }
        }

        // Open a URL
        Regex("https?://\\S+").find(task)?.let { m ->
            val rest = Text.norm(task.replace(m.value, "")).split(' ').filter { it.isNotBlank() && it !in OPEN_VERBS && it != "الرابط" }
            if (rest.isEmpty() && start(Intent(Intent.ACTION_VIEW, Uri.parse(m.value)))) return Result.Handled("تم فتح الرابط")
        }

        // Dial a number (the owner presses call)
        Regex("^(اتصل|كلم|call)\\s*(ب|على|علي|to)?\\s*([+0-9][0-9 \\-]{5,})$").find(t)?.let { m ->
            val num = m.groupValues[3].replace(" ", "").replace("-", "")
            if (start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))) return Result.Handled("فُتحت لوحة الاتصال بالرقم $num — اضغط اتصال للتأكيد")
        }

        // Alarm
        if (words.size <= 9 && Regex("(منبه|نبهني|صحيني|alarm)").containsMatchIn(t)) {
            Regex("(\\d{1,2})(?:[:.](\\d{2}))?").find(t)?.let { m ->
                var hour = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                if (hour < 12 && Regex("(مساء|مسا|\\bم\\b|pm|ليلا|بالليل|العصر|المغرب|العشاء)").containsMatchIn(t)) hour += 12
                if (hour in 0..23 && min in 0..59) {
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, min)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "وكيل")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (start(i)) return Result.Handled(String.format(Locale.US, "تم ضبط منبه الساعة %02d:%02d", hour, min))
                }
            }
        }

        // Timer
        if (words.size <= 8 && Regex("(موقت|تايمر|timer)").containsMatchIn(t)) {
            Regex("(\\d+)\\s*(ثانيه|ثواني|ثوان|sec|دقيقه|دقايق|min|ساعه|ساعات|hour)").find(t)?.let { m ->
                val n = m.groupValues[1].toInt()
                val unit = m.groupValues[2]
                val secs = when {
                    unit.startsWith("ث") || unit == "sec" -> n
                    unit.startsWith("س") || unit == "hour" -> n * 3600
                    else -> n * 60
                }
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (secs > 0 && start(i)) return Result.Handled("تم تشغيل مؤقت ${m.value}")
            }
        }

        // Web search
        if (words.isNotEmpty() && words.first() in SEARCH_VERBS && words.any { it in SEARCH_ENGINES }) {
            val q = words.filter { it !in SEARCH_VERBS && it !in SEARCH_ENGINES && it !in SEARCH_FILLERS }.joinToString(" ")
            if (q.isNotBlank() && start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))) return Result.Handled("تم البحث عن «$q»")
        }

        // Quick settings panels
        val bare = t.removePrefix("افتح ").trim()
        when (bare) {
            in P_WIFI -> if (start(Intent(Settings.Panel.ACTION_WIFI))) return Result.Handled("تم فتح لوحة الواي فاي")
            in P_BT -> if (start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) return Result.Handled("تم فتح إعدادات البلوتوث")
            in P_NET -> if (start(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) return Result.Handled("تم فتح لوحة الإنترنت")
            in P_VOL -> if (start(Intent(Settings.Panel.ACTION_VOLUME))) return Result.Handled("تم فتح لوحة الصوت")
        }

        // Open an app (optionally followed by more instructions)
        if (words.size >= 2 && words.first() in OPEN_VERBS) {
            val rest = words.drop(1)
            for (k in minOf(rest.size, 4) downTo 1) {
                val app = apps.exact(rest.take(k).joinToString(" ")) ?: continue
                if (!apps.launch(app.pkg)) break
                val remaining = rest.drop(k).joinToString(" ").trim()
                return if (remaining.isEmpty()) Result.Handled("تم فتح ${app.label}") else Result.Opened(app.label, remaining)
            }
        }
        return Result.None
    }

    private fun start(i: Intent): Boolean = runCatching {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(ctx.packageManager) == null) return false
        (AgentAccessibilityService.instance ?: ctx).startActivity(i)
        true
    }.getOrDefault(false)

    companion object {
        private fun set(vararg s: String) = s.map { Text.norm(it) }.toSet()
        private val OPEN_VERBS = set("افتح", "شغل", "افتحي", "روح", "ادخل", "open", "launch", "start")
        private val SEARCH_VERBS = set("ابحث", "بحث", "دور", "دورلي", "search", "google")
        private val SEARCH_ENGINES = set("جوجل", "قوقل", "google", "الانترنت", "النت")
        private val SEARCH_FILLERS = set("عن", "في", "على", "علي", "for", "on", "in")
        private val NAV_BACK = set("ارجع", "رجوع", "للخلف", "رجعني", "back")
        private val NAV_HOME = set("الرئيسية", "الشاشة الرئيسية", "اذهب للرئيسية", "روح للرئيسية", "home")
        private val NAV_NOTIF = set("الإشعارات", "افتح الإشعارات", "اعرض الإشعارات", "notifications")
        private val NAV_RECENTS = set("التطبيقات الأخيرة", "التطبيقات المفتوحة", "recents")
        private val NAV_SHOT = set("لقطة شاشة", "صور الشاشة", "خذ لقطة شاشة", "screenshot")
        private val NAV_LOCK = set("اقفل الشاشة", "قفل الشاشة", "lock")
        private val P_WIFI = set("الواي فاي", "واي فاي", "الوايفاي", "wifi", "إعدادات الواي فاي")
        private val P_BT = set("البلوتوث", "بلوتوث", "bluetooth")
        private val P_NET = set("الإنترنت", "بيانات الجوال", "البيانات")
        private val P_VOL = set("الصوت", "مستوى الصوت")
    }
}
