package com.wakeel.agent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
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
class LocalRouter(private val ctx: Context, private val apps: AppIndex) {

    sealed interface Result {
        data class Handled(val message: String) : Result
        /** An app or page was opened locally; the model continues with [rest]. */
        data class Opened(val app: String, val pkg: String, val rest: String, val step: MacroStep) : Result
        data object None : Result
    }

    suspend fun route(task: String, svc: AgentAccessibilityService): Result {
        when (val cmd = Commands.parse(task)) {
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
        // Open an app (optionally followed by more instructions)
        val words = t.split(' ').filter { it.isNotBlank() }
        if (words.size >= 2 && words.first() in Commands.OPEN_VERBS) {
            val rest = words.drop(1)
            for (k in minOf(rest.size, 4) downTo 1) {
                val app = apps.exact(rest.take(k).joinToString(" ")) ?: continue
                if (!apps.launch(app.pkg)) break
                val remaining = rest.drop(k).joinToString(" ").trim()
                return if (remaining.isEmpty()) Result.Handled("تم فتح ${app.label}") else Result.Opened(app.label, app.pkg, remaining, MacroStep("open_app", text = app.pkg, label = app.label))
            }
        }
        return Result.None
    }

    // ------------------------------------------------------------------ switches

    private suspend fun toggle(cmd: Cmd.Toggle, task: String, svc: AgentAccessibilityService): Result {
        val page = SettingsCatalog.page(cmd.key) ?: return Result.None
        if (!openSettings(cmd.key).first) return Result.None
        svc.waitForIdle(600, 4000)
        val words = page.words.map { Text.norm(it) }
        repeat(5) {
            val scr = svc.readScreen()
            val switches = scr.nodes.filter { it.role == "sw" }
            val onDedicatedPage = scr.nodes.any { n ->
                n.bounds.centerY() < scr.height * 0.22f && Text.norm(n.label).let { l -> l.isNotEmpty() && words.any { w -> l.contains(w) } }
            }
            val sw: UiNode? = when {
                switches.size == 1 && onDedicatedPage -> switches[0]
                else -> switches.firstOrNull { n -> Text.norm(n.label).let { l -> words.any { w -> l.contains(w) } } }
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
        // The page is open but its switch was not recognised — let the model finish (one reply).
        return Result.Opened("الإعدادات", "com.android.settings", task, MacroStep("open_settings", text = cmd.key, label = page.ar))
    }

    private suspend fun click(svc: AgentAccessibilityService, n: UiNode) {
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
        for (a in page.actions) if (start(Intent(a))) return true to page.ar
        return start(Intent("android.settings.SETTINGS")) to page.ar
    }

    fun appInfo(pkg: String): Boolean =
        start(Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:$pkg")))

    /** Opens a web link or a known deep link. `tel:` only opens the dialer; nothing is ever sent or called. */
    fun openUrl(rawUrl: String): Boolean {
        val url = normalizeUrl(rawUrl) ?: return false
        val uri = Uri.parse(encodeLoose(url))
        val intent = if (url.startsWith("tel:", ignoreCase = true)) Intent(Intent.ACTION_DIAL, uri) else Intent(Intent.ACTION_VIEW, uri)
        return start(intent)
    }

    private fun torch(on: Boolean): Boolean = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return false
        cm.setTorchMode(id, on)
        true
    }.getOrDefault(false)

    private fun start(i: Intent): Boolean = runCatching {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (AgentAccessibilityService.instance ?: ctx).startActivity(i)
        true
    }.getOrDefault(false)

    companion object {
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
