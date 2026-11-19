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
import com.wakeel.agent.Hub
import com.wakeel.agent.core.Shot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    @Volatile
    private var lastEvent = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        overlay = Overlay(this).also { if (Hub.settings.showBubble) it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return
        lastEvent = SystemClock.uptimeMillis()
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
        Hub.engine.stop()
        overlay?.hide()
        overlay?.dispose()
        overlay = null
        instance = null
        _connected.value = false
    }

    fun setBubbleVisible(visible: Boolean) = main.post { if (visible) overlay?.show() else overlay?.hide() }

    // ---------------- Reading ----------------

    fun screenSize(): Pair<Int, Int> {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    fun readScreen(): Screen {
        val (w, h) = screenSize()
        return ScreenReader.read(this, w, h)
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

    private suspend fun gesture(g: GestureDescription): Boolean = withContext(Dispatchers.Main) {
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
        val bmp = withContext(Dispatchers.Main) {
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
        } ?: return null
        return withContext(Dispatchers.Default) {
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
}
