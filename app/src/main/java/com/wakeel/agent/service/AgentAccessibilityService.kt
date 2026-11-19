package com.wakeel.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wakeel.agent.Hub
import com.wakeel.agent.agent.AppMapStore
import com.wakeel.agent.agent.Safety
import com.wakeel.agent.core.Policy
import com.wakeel.agent.core.Shot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun isEnabledInSettings(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, AgentAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    private var overlay: Overlay? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastEvent = 0L

    /** Short class name of the foreground activity, tracked from window events. */
    @Volatile
    private var activity = ""

    @Volatile
    private var activityPkg = ""

    /** When another app last showed a new window, and which app it was. */
    @Volatile
    private var lastWindowChange = 0L

    @Volatile
    private var lastWindowPkg = ""

    // Passive learning (only when the owner enabled it): last thing the owner tapped and where.
    private var passiveJob: Job? = null

    @Volatile
    private var pendingClick: Triple<String, String, Long>? = null   // pkg, label, time

    @Volatile
    private var lastPassiveKey: Pair<String, String>? = null        // pkg, screen key

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        overlay = Overlay(this).also { if (Hub.settings.showBubble) it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        lastEvent = SystemClock.uptimeMillis()
        // Learning by demonstration: while the owner shows a task by hand, their steps are recorded
        val recording = Hub.recorder.active
        if (recording) runCatching { Hub.recorder.onEvent(event) }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastWindowChange = lastEvent
                lastWindowPkg = pkg
                val cls = event.className?.toString().orEmpty()
                val isWindowChrome = cls.startsWith("android.") || cls.contains("Dialog") || cls.contains("Popup") ||
                    cls.contains("Menu") || cls.contains("Toast") || !cls.contains('.')
                if (!isWindowChrome) {
                    activity = cls.substringAfterLast('.')
                    activityPkg = pkg
                } else if (pkg != activityPkg) {
                    activity = ""
                    activityPkg = pkg
                }
                schedulePassive()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (Hub.settings.passiveLearn || recording) {
                pendingClick = Triple(pkg, clickLabel(event), SystemClock.uptimeMillis())
                schedulePassive()
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance == null) return
        Hub.recorder.stop(save = false)
        Hub.engine.stop()
        Hub.maps.flush()
        scope.cancel()
        overlay?.hide()
        overlay?.dispose()
        overlay = null
        instance = null
        _connected.value = false
    }

    fun setBubbleVisible(visible: Boolean) = main.post { if (visible) overlay?.show() else overlay?.hide() }

    // ---------------- Passive learning ----------------

    private fun clickLabel(e: AccessibilityEvent): String {
        // List items are content (chat names, posts…), never stored — only the fact that an item was opened.
        var n: AccessibilityNodeInfo? = runCatching { e.source }.getOrNull()
        repeat(10) {
            val c = n ?: return@repeat
            val cls = c.className?.toString().orEmpty()
            if (c.isScrollable && !(cls.endsWith("ScrollView") || cls.contains("Pager"))) return AppMapStore.ITEM
            n = c.parent
        }
        val label = (e.contentDescription?.toString() ?: e.text?.joinToString(" ") { it.toString() }.orEmpty()).trim()
        if (label.isNotEmpty()) return label
        // An icon without text: remembered by its view id
        val id = runCatching { e.source?.viewIdResourceName }.getOrNull()?.substringAfter(":id/").orEmpty()
        return if (id.isEmpty()) "" else "#$id"
    }

    private fun schedulePassive() {
        // The map is filled while the owner uses the phone only if they enabled that — or while they are teaching a task
        if (!(Hub.settings.passiveLearn || Hub.recorder.active) || !Hub.settings.autoLearn || Hub.engine.isRunning) return
        passiveJob?.cancel()
        passiveJob = scope.launch {
            delay(900)
            if (Hub.engine.isRunning) return@launch
            runCatching {
                val s = readScreen()
                if (s.pkg.isBlank() || s.pkg == packageName || s.hasPassword) return@runCatching
                if (s.pkg in Hub.apps.launcherPackages() || s.pkg == "com.android.systemui") return@runCatching
                if (Safety.policy(s.pkg, Hub.settings, Hub.apps) == Policy.BLOCKED) return@runCatching
                val key = Hub.maps.observe(s) ?: return@runCatching
                val click = pendingClick
                val prev = lastPassiveKey
                if (click != null && prev != null && click.first == s.pkg && prev.first == s.pkg &&
                    SystemClock.uptimeMillis() - click.third < 5000 && prev.second != key
                ) {
                    Hub.maps.edge(s.pkg, prev.second, click.second.ifBlank { AppMapStore.ITEM }, key)
                }
                pendingClick = null
                lastPassiveKey = s.pkg to key
                Hub.maps.flush(force = false)
            }
        }
    }

    // ---------------- Reading ----------------

    fun screenSize(): Pair<Int, Int> {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    fun readScreen(): Screen {
        val (w, h) = screenSize()
        return ScreenReader.read(this, w, h) { pkg -> if (pkg.isNotEmpty() && pkg == activityPkg) activity else "" }
    }

    /** Package of the app window in front, or null while nothing is readable yet. */
    fun frontPackage(): String? = ScreenReader.root(this)?.packageName?.toString()

    /**
     * After starting an app or a page at [startedAt] (uptime): waits until its window has actually appeared,
     * can be read, and has stopped changing. Waiting only "until idle" returns too early — right after the
     * launch nothing has started to change yet, and the previous screen would be read as if it were the new one.
     * [pkg] is the expected app when known.
     */
    suspend fun waitForApp(pkg: String?, startedAt: Long, maxMs: Long = 7000) {
        val deadline = startedAt + maxMs
        // 1) a new window shows up (if none does within a moment, the page was already in front)
        while (SystemClock.uptimeMillis() < deadline && lastWindowChange < startedAt && SystemClock.uptimeMillis() - startedAt < 2500) delay(80)
        // 2) it is readable, and it is the app we asked for
        val want = pkg ?: lastWindowPkg.takeIf { lastWindowChange >= startedAt }
        while (SystemClock.uptimeMillis() < deadline) {
            val front = frontPackage()
            if (front != null && front != packageName && (want == null || front == want)) break
            delay(150)
        }
        // 3) it stops changing
        waitForIdle(450, (deadline - SystemClock.uptimeMillis()).coerceIn(600, 3000))
    }

    /**
     * While the owner teaches a task, events are delivered at once instead of batched, so the element
     * they tapped can still be read before its page closes.
     */
    fun setFastEvents(on: Boolean) {
        runCatching {
            val info = serviceInfo ?: return
            info.notificationTimeout = if (on) 0 else 100
            serviceInfo = info
        }
    }

    /** Waits until the UI stops changing (no accessibility events for [quietMs]) or [maxMs] passes. */
    suspend fun waitForIdle(quietMs: Long = 400, maxMs: Long = 3000) {
        val start = SystemClock.uptimeMillis()
        delay(150)
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - lastEvent >= quietMs) return
            delay(100)
        }
    }

    // ---------------- Acting ----------------

    fun global(action: Int): Boolean = performGlobalAction(action)

    suspend fun tap(x: Float, y: Float, long: Boolean = false): Boolean {
        val (w, h) = screenSize()
        val path = Path().apply { moveTo(x.coerceIn(1f, w - 1f), y.coerceIn(1f, h - 1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, if (long) 800 else 60)
        return gesture(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 350): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build())
    }

    /** Runs [block] with our overlays hidden and click-through, then restores them. */
    private suspend fun <T> clearOfOverlays(block: suspend () -> T): T {
        val o = overlay ?: return block()
        withContext(Dispatchers.Main) { o.setSuspended(true) }
        delay(80)
        try {
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { o.setSuspended(false) }
        }
    }

    private suspend fun gesture(g: GestureDescription): Boolean = clearOfOverlays { dispatch(g) }

    private suspend fun dispatch(g: GestureDescription): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    // ---------------- Screenshot ----------------

    suspend fun screenshot(maxSide: Int = 768, quality: Int = 55): Shot? {
        val bmp = clearOfOverlays { capture() } ?: return null
        return encode(bmp, maxSide, quality)
    }

    private suspend fun capture(): Bitmap? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val b = runCatching {
                            val hb = result.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            copy
                        }.getOrNull()
                        if (cont.isActive) cont.resume(b)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }

    private suspend fun encode(bmp: Bitmap, maxSide: Int, quality: Int): Shot =
        withContext(Dispatchers.Default) {
            val scale = max(bmp.width, bmp.height).toFloat() / maxSide
            val f = if (scale > 1f) scale else 1f
            val sw = (bmp.width / f).toInt()
            val sh = (bmp.height / f).toInt()
            val small = if (f > 1f) Bitmap.createScaledBitmap(bmp, sw, sh, true) else bmp
            val bos = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            if (small !== bmp) small.recycle()
            bmp.recycle()
            Shot(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), sw, sh, f, f)
        }
}
