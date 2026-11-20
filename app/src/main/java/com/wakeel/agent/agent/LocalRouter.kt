package com.wakeel.agent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.telephony.TelephonyManager
import com.wakeel.agent.core.DeviceInfo
import com.wakeel.agent.core.Text
import com.wakeel.agent.service.AgentAccessibilityService
import com.wakeel.agent.service.UiNode
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.util.Locale

/**
 * Executes everyday commands directly with Android APIs — zero API calls.
 * Anything it cannot fully handle goes to the model (optionally after opening the right app or page first).
 */
class LocalRouter(private val ctx: Context, private val apps: AppIndex, private val maps: AppMapStore? = null) {

    sealed interface Result {
        data class Handled(val message: String) : Result
        /** An app or page was opened locally; the model continues with [rest]. [fresh] = the app was restarted on its first page. */
        data class Opened(val app: String, val pkg: String, val rest: String, val step: MacroStep, val fresh: Boolean = false) : Result
        /** A page the agent already knows how to reach in this app: [steps] are replayed with zero API calls. */
        data class Path(val app: String, val pkg: String, val page: String, val steps: List<MacroStep>) : Result
        /** The page was opened, but the agent may not operate it; the owner has to finish by hand. */
        data class Blocked(val message: String) : Result
        data object None : Result
    }

    suspend fun route(task: String, svc: AgentAccessibilityService): Result {
        when (val cmd = Commands.parse(task, dialCode())) {
            is Cmd.Global -> {
                val action = when (cmd.action) {
                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                    "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    else -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                }
                if (cmd.action == "screenshot") delay(600)   // let our own window close first
                if (svc.global(action)) return Result.Handled(cmd.say)
            }
            is Cmd.Info -> return Result.Handled(DeviceInfo.answer(cmd.kind, DeviceInfo.snapshot(ctx)))
            is Cmd.Health -> return Result.Handled(DeviceInfo.report(DeviceInfo.snapshot(ctx)))
            is Cmd.Torch -> if (torch(cmd.on)) return Result.Handled(if (cmd.on) "تم تشغيل الكشاف" else "تم إطفاء الكشاف")
            is Cmd.Volume -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val ok = runCatching {
                    when (cmd.dir) {
                        1 -> repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                        -1 -> repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                        else -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    }
                }.isSuccess
                if (ok) return Result.Handled(when (cmd.dir) { 1 -> "تم رفع الصوت"; -1 -> "تم خفض الصوت"; else -> "تم كتم صوت الوسائط" })
            }
            is Cmd.Media -> {
                val code = when (cmd.key) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                }
                val am = ctx.getSystemService(AudioManager::class.java)
                val ok = runCatching {
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }.isSuccess
                if (ok) return Result.Handled(when (cmd.key) { "play" -> "تم التشغيل"; "pause" -> "تم الإيقاف المؤقت"; "next" -> "المقطع التالي"; else -> "المقطع السابق" })
            }
            is Cmd.Url -> if (openUrl(cmd.url)) return Result.Handled(cmd.say)
            is Cmd.Alarm -> {
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, cmd.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "وكيل")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (start(i)) return Result.Handled(String.format(Locale.US, "تم ضبط منبه الساعة %02d:%02d", cmd.hour, cmd.minute))
            }
            is Cmd.Timer -> {
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (start(i)) return Result.Handled("تم تشغيل مؤقت ${cmd.label}")
            }
            is Cmd.SettingsPage -> {
                val (ok, label) = openSettings(cmd.key)
                if (ok) return Result.Handled("فُتحت صفحة $label")
            }
            is Cmd.Toggle -> return toggle(cmd, task, svc)
            null -> Unit
        }

        val t = Text.norm(task).trimEnd('.', '!', '؟', '?')
        // App details page: "معلومات تطبيق X"
        Regex("^(افتح\\s+)?(معلومات|اعدادات|صلاحيات|اذونات|اشعارات)\\s+(تطبيق|برنامج)\\s+(.+)$").find(t)?.let { m ->
            apps.fuzzy(m.groupValues[4])?.let { app ->
                if (appInfo(app.pkg)) return Result.Handled("فُتحت صفحة معلومات «${app.label}»")
            }
        }
        val words = t.split(' ').filter { it.isNotBlank() }
        // A page inside an app: "افتح الملف الشخصي في انستقرام". If the learned map knows the way, it is replayed for free.
        pageInApp(words)?.let { (app, page) ->
            val step = MacroStep("open_app", text = app.pkg, label = app.label)
            val path = maps?.findPath(app.pkg, page)
            if (path != null) {
                return Result.Path(app.label, app.pkg, page, listOf(step) + path.map { MacroStep("tap", "|${it.id}|${it.label}", label = it.label) })
            }
            if (apps.launch(app.pkg, fresh = true)) return Result.Opened(app.label, app.pkg, page, step, fresh = true)
        }
        // Open an app (optionally followed by more instructions)
        if (words.size >= 2 && words.first() in Commands.OPEN_VERBS) {
            val rest = words.drop(1)
            for (k in minOf(rest.size, 4) downTo 1) {
                val app = apps.exact(rest.take(k).joinToString(" ")) ?: continue
                val remaining = rest.drop(k).joinToString(" ").trim()
                // A task that continues inside the app starts from the app's first page, like its saved routes will
                if (!apps.launch(app.pkg, fresh = remaining.isNotEmpty())) break
                return if (remaining.isEmpty()) Result.Handled("تم فتح ${app.label}")
                else Result.Opened(app.label, app.pkg, remaining, MacroStep("open_app", text = app.pkg, label = app.label), fresh = true)
            }
        }
        return Result.None
    }

    /** "<open verb> <page> في <app>" → the app and the page name; null for anything else. */
    private fun pageInApp(words: List<String>): Pair<AppIndex.App, String>? {
        if (words.size < 4 || words.first() !in Commands.OPEN_VERBS) return null
        for (i in words.size - 2 downTo 2) {
            if (words[i] !in IN_WORDS) continue
            val appName = words.drop(i + 1).filter { it !in APP_WORDS }.joinToString(" ")
            val app = apps.exact(appName) ?: continue
            val page = words.subList(1, i).dropWhile { it in PAGE_LEAD }.joinToString(" ").trim()
            if (page.length >= 2) return app to page
        }
        return null
    }

    // ------------------------------------------------------------------ switches

    private suspend fun toggle(cmd: Cmd.Toggle, task: String, svc: AgentAccessibilityService): Result {
        val page = SettingsCatalog.page(cmd.key) ?: return Result.None
        val t0 = SystemClock.uptimeMillis()
        if (!openSettings(cmd.key).first) return Result.None
        svc.waitForApp(null, t0, 9000)
        // Android hides some settings pages from control apps: say so instead of spending model calls on a page nobody can read
        val hidden = Result.Blocked(
            "فتحتُ صفحة ${page.ar}، لكنّ أندرويد يحجب محتوى هذه الصفحة عن تطبيقات التحكم، فلا أستطيع تبديل المفتاح. " +
                "الصفحة مفتوحة أمامك: بدّله بيدك."
        )
        if (svc.unreadableWindow() != null) {
            delay(1500)
            if (svc.unreadableWindow() != null) return hidden
        }
        val words = page.words.map { Text.norm(it) }
        repeat(8) {
            val scr = svc.readScreen()
            val switches = scr.nodes.filter { it.role == "sw" }
            val onDedicatedPage = scr.nodes.any { n ->
                n.bounds.centerY() < scr.height * 0.22f && Text.norm(n.label).let { l -> l.isNotEmpty() && words.any { w -> l.contains(w) } }
            }
            fun named(l: String) = Text.norm(l).let { n -> n.isNotEmpty() && words.any { w -> n.contains(w) } }
            // Many phones draw the switch as a bare toggle inside a row; the row carries the name
            fun inNamedRow(sw: UiNode) = scr.nodes.any { row ->
                row !== sw && row.bounds.height() < scr.height / 5 && row.bounds.top <= sw.bounds.centerY() && row.bounds.bottom >= sw.bounds.centerY() && named(row.label)
            }
            val sw: UiNode? = when {
                switches.size == 1 && onDedicatedPage -> switches[0]
                else -> switches.firstOrNull { named(it.label) }
                    ?: switches.firstOrNull { inNamedRow(it) }
                    ?: if (onDedicatedPage) switches.filter { it.bounds.top < scr.height * 0.4f }.minByOrNull { it.bounds.top } else null
            }
            if (sw != null) {
                val state = if (cmd.on) "مفعّل" else "متوقف"
                if (sw.checked == cmd.on) return Result.Handled("${page.ar} $state بالفعل")
                click(svc, sw)
                svc.waitForIdle(500, 2500)
                val now = svc.readScreen().nodes.filter { it.role == "sw" }.minByOrNull {
                    kotlin.math.abs(it.bounds.centerY() - sw.bounds.centerY()) + kotlin.math.abs(it.bounds.centerX() - sw.bounds.centerX())
                }
                return if (now != null && now.checked == cmd.on) Result.Handled(if (cmd.on) "تم تشغيل ${page.ar}" else "تم إيقاف ${page.ar}")
                else Result.Opened("الإعدادات", "com.android.settings", task, MacroStep("open_settings", text = cmd.key, label = page.ar))
            }
            delay(400)
        }
        if (svc.unreadableWindow() != null) return hidden
        // The page is open but its switch was not recognised — let the model finish (one reply).
        return Result.Opened("الإعدادات", "com.android.settings", task, MacroStep("open_settings", text = cmd.key, label = page.ar))
    }

    private suspend fun click(svc: AgentAccessibilityService, n: UiNode) {
        svc.markAction()
        var cur: AccessibilityNodeInfo? = n.info
        if (n.info.refresh()) {
            repeat(5) {
                val c = cur ?: return@repeat
                if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                cur = c.parent
            }
        }
        svc.tap(n.bounds.exactCenterX(), n.bounds.exactCenterY())
    }

    // ------------------------------------------------------------------ shared openers (also used by the engine)

    /** Opens a settings page by key (see [SettingsCatalog]) or `app_info:<app>`. Returns success and a display name. */
    fun openSettings(key: String): Pair<Boolean, String> {
        val k = key.trim()
        if (k.startsWith("app_info", ignoreCase = true)) {
            val name = k.substringAfter(':', "").trim()
            val app = apps.fuzzy(name) ?: return false to name
            return appInfo(app.pkg) to "معلومات ${app.label}"
        }
        val page = SettingsCatalog.page(k)
            ?: SettingsCatalog.keyForWords(Text.norm(k))?.let { SettingsCatalog.page(it) }
            ?: return false to k
        // A clean task: the page is not stacked on whatever Settings was showing before, so saved routes always start the same way
        for (a in page.actions) if (start(Intent(a), clean = true)) return true to page.ar
        return start(Intent("android.settings.SETTINGS"), clean = true) to page.ar
    }

    fun appInfo(pkg: String): Boolean =
        start(Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:$pkg")), clean = true)

    /** Opens a web link or a known deep link. `tel:` only opens the dialer; nothing is ever sent or called. */
    /** This phone's country calling code, from its SIM (no permission needed). */
    private fun dialCode(): String? = runCatching {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        Dial.code(tm.simCountryIso.ifBlank { tm.networkCountryIso })
    }.getOrNull()

    fun openUrl(rawUrl: String): Boolean {
        val url = Dial.waLink(normalizeUrl(rawUrl) ?: return false, dialCode())
        val uri = Uri.parse(encodeLoose(url))
        val intent = if (url.startsWith("tel:", ignoreCase = true)) Intent(Intent.ACTION_DIAL, uri) else Intent(Intent.ACTION_VIEW, uri)
        // WhatsApp and WhatsApp Business both installed: a chat link would stop at "open with…".
        // The main app is the one meant, so the link goes straight to it.
        val host = runCatching { uri.host?.lowercase() }.getOrNull()
        if (host == "wa.me" || host == "api.whatsapp.com" || uri.scheme == "whatsapp") {
            val pm = ctx.packageManager
            if (pm.getLaunchIntentForPackage(WHATSAPP) != null && pm.getLaunchIntentForPackage("$WHATSAPP.w4b") != null) {
                if (start(Intent(intent).setPackage(WHATSAPP))) return true
            }
        }
        return start(intent)
    }

    private fun torch(on: Boolean): Boolean = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return false
        cm.setTorchMode(id, on)
        true
    }.getOrDefault(false)

    private fun start(i: Intent, clean: Boolean = false): Boolean = runCatching {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (clean) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0))
        (AgentAccessibilityService.instance ?: ctx).startActivity(i)
        true
    }.getOrDefault(false)

    companion object {
        private const val WHATSAPP = "com.whatsapp"
        private val IN_WORDS = setOf("في", "من", "داخل", "ب", "علي", "ضمن", "in", "inside", "of").map { Text.norm(it) }.toSet()
        private val APP_WORDS = setOf("تطبيق", "برنامج", "app").map { Text.norm(it) }.toSet()
        private val PAGE_LEAD = setOf("إلى", "الى", "ل", "على", "صفحة", "قسم", "تبويب", "قائمة", "شاشة", "to", "the", "page", "tab", "screen").map { Text.norm(it) }.toSet()

        private val SCHEMES = setOf(
            "http", "https", "geo", "google.navigation", "tel", "sms", "smsto", "mailto", "market",
            "instagram", "whatsapp", "tg", "twitter", "vnd.youtube", "youtube", "fb", "snapchat", "spotify", "zoomus", "linkedin",
        )

        /** Returns the link with an allowed scheme (https added to bare domains), or null. */
        fun normalizeUrl(raw: String): String? {
            var u = raw.trim().trim('"', '\'', '<', '>')
            if (u.isEmpty()) return null
            if (!u.contains(':') || Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(/.*)?$").matches(u)) u = "https://$u"
            val scheme = u.substringBefore(':').lowercase()
            if (scheme !in SCHEMES) return null
            return u
        }

        /** Percent-encodes spaces and non-ASCII so any app accepts the link. */
        fun encodeLoose(u: String): String = buildString {
            var i = 0
            while (i < u.length) {
                val cp = u.codePointAt(i)
                val len = Character.charCount(cp)
                val s = u.substring(i, i + len)
                when {
                    cp == 32 -> append("%20")
                    cp > 127 -> append(URLEncoder.encode(s, "UTF-8"))
                    else -> append(s)
                }
                i += len
            }
        }
    }
}
