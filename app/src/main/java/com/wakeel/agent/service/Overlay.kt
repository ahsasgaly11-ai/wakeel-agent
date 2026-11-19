package com.wakeel.agent.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.wakeel.agent.Hub
import com.wakeel.agent.agent.EngineState
import com.wakeel.agent.ui.CommandActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Floating bubble + quick-command panel drawn as accessibility overlays (no extra permission needed). */
class Overlay(private val svc: AgentAccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val density = svc.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var spin: ObjectAnimator? = null
    private var stateJob: Job? = null

    private var panel: LinearLayout? = null
    private var statusTv: TextView? = null
    private var questionTv: TextView? = null
    private var confirmRow: LinearLayout? = null
    private var mainBtn: TextView? = null
    private var stopBtn: TextView? = null

    private var lastState: EngineState = EngineState.Idle

    // ---------- Colors ----------
    private val indigo = intArrayOf(0xFF6366F1.toInt(), 0xFF4338CA.toInt())
    private val teal = intArrayOf(0xFF2DD4BF.toInt(), 0xFF0F766E.toInt())
    private val amber = intArrayOf(0xFFFBBF24.toInt(), 0xFFD97706.toInt())

    fun show() {
        if (bubble != null) return
        val size = dp(56)
        val b = TextView(svc).apply {
            text = "✦"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = oval(indigo)
            elevation = dp(8).toFloat()
            contentDescription = "وكيل"
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(6)
            y = dp(240)
        }
        b.setOnTouchListener(DragListener())
        runCatching { wm.addView(b, lp) }.onFailure { return }
        bubble = b
        bubbleLp = lp
        stateJob = scope.launch { Hub.engine.state.collect { render(it) } }
    }

    fun hide() {
        stateJob?.cancel()
        stateJob = null
        closePanel()
        spin?.cancel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    fun dispose() = scope.cancel()

    /** Makes bubble + panel invisible and click-through so they never block a gesture or appear in a screenshot. */
    fun setSuspended(suspended: Boolean) {
        listOfNotNull(bubble, panel).forEach { v ->
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            lp.flags = if (suspended) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            v.alpha = if (suspended) 0f else 1f
            runCatching { wm.updateViewLayout(v, lp) }
        }
    }

    // ---------- Bubble drag / tap ----------
    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private val slop = ViewConfiguration.get(svc).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = bubbleLp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                        when {
                            panel != null -> closePanel()
                            lastState is EngineState.Idle -> CommandActivity.open(svc)
                            else -> openPanel()
                        }
                    } else {
                        // snap to nearest edge
                        val w = svc.screenSize().first
                        lp.x = if (lp.x + v.width / 2 < w / 2) dp(6) else w - v.width - dp(6)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
            }
            return true
        }
    }

    // ---------- Panel ----------
    private fun openPanel() {
        if (panel != null) return
        val p = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xF5111827.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(svc).apply {
            text = "✦  وكيل"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(svc).apply {
            text = "✕"
            textSize = 18f
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { closePanel() }
        })
        p.addView(header)

        statusTv = TextView(svc).apply {
            setOnClickListener {
                if (lastState is EngineState.Idle) Hub.history.records.value.firstOrNull()?.let {
                    closePanel(); com.wakeel.agent.ui.ResultActivity.open(svc, it.id)
                }
            }
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, dp(10))
        }
        p.addView(statusTv)

        questionTv = TextView(svc).apply {
            textSize = 15f
            setTextColor(0xFFFDE68A.toInt())
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
            setPadding(0, 0, 0, dp(10))
        }
        p.addView(questionTv)

        confirmRow = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
            addView(pill("سماح", 0xFF10B981.toInt()) { Hub.engine.answerConfirm(true) }, weightLp(end = dp(8)))
            addView(pill("رفض", 0xFFEF4444.toInt()) { Hub.engine.answerConfirm(false) }, weightLp())
        }
        p.addView(confirmRow)

        val actions = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        mainBtn = pill("مهمة جديدة", 0xFF6366F1.toInt()) { onMain() }
        stopBtn = pill("إيقاف", 0xFFEF4444.toInt()) { Hub.engine.stop() }
        actions.addView(mainBtn, weightLp(end = dp(8)))
        actions.addView(stopBtn, weightLp())
        p.addView(actions)

        val (w, h) = svc.screenSize()
        val by = bubbleLp?.y ?: dp(240)
        val below = by < h / 2
        val lp = WindowManager.LayoutParams(
            minOf(dp(340), w - dp(24)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (below) {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = by + dp(64)
            } else {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (h - by + dp(8)).coerceAtLeast(dp(8))
            }
        }
        p.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && lastState is EngineState.Idle) {
                closePanel(); true
            } else false
        }
        runCatching { wm.addView(p, lp) }.onFailure { return }
        panel = p
        render(lastState)
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        statusTv = null; questionTv = null; confirmRow = null; mainBtn = null; stopBtn = null
    }

    private fun onMain() {
        when (val st = lastState) {
            is EngineState.Idle -> { closePanel(); CommandActivity.open(svc) }
            is EngineState.Ask -> { closePanel(); CommandActivity.open(svc, st.question) }
            else -> {}
        }
    }

    // ---------- Rendering ----------
    private fun render(s: EngineState) {
        val prev = lastState
        lastState = s
        if (s is EngineState.Ask && prev !is EngineState.Ask) {
            closePanel()
            CommandActivity.open(svc, s.question)
        }
        val b = bubble ?: return
        when (s) {
            is EngineState.Idle -> { b.background = oval(indigo); stopSpin() }
            is EngineState.Running -> { b.background = oval(teal); startSpin() }
            is EngineState.Confirm -> {
                b.background = oval(amber); stopSpin()
                if (panel == null) openPanel()
            }
            is EngineState.Ask -> { b.background = oval(amber); stopSpin() }
        }
        if (panel == null) return
        val result = Hub.engine.lastResult.value
        statusTv?.text = when (s) {
            is EngineState.Idle -> result?.let { "آخر نتيجة: ${it.lines().first().take(90)}\nاضغط هنا لعرضها كاملة" } ?: "جاهز."
            is EngineState.Running -> "الخطوة ${s.step}/${s.max} — ${s.line}"
            is EngineState.Confirm -> "بانتظار موافقتك قبل المتابعة"
            is EngineState.Ask -> "الوكيل يحتاج معلومة منك"
        }
        questionTv?.apply {
            val q = when (s) { is EngineState.Confirm -> s.question; is EngineState.Ask -> s.question; else -> null }
            text = q.orEmpty()
            visibility = if (q != null) View.VISIBLE else View.GONE
        }
        confirmRow?.visibility = if (s is EngineState.Confirm) View.VISIBLE else View.GONE
        mainBtn?.visibility = if (s is EngineState.Idle || s is EngineState.Ask) View.VISIBLE else View.GONE
        mainBtn?.text = if (s is EngineState.Ask) "اكتب الرد" else "مهمة جديدة"
        stopBtn?.visibility = if (s is EngineState.Idle) View.GONE else View.VISIBLE
    }

    private fun startSpin() {
        val b = bubble ?: return
        if (spin?.isRunning == true) return
        spin = ObjectAnimator.ofFloat(b, View.ROTATION, 0f, 360f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopSpin() {
        spin?.cancel()
        spin = null
        bubble?.rotation = 0f
    }

    // ---------- Helpers ----------
    private fun oval(c: IntArray) = GradientDrawable(GradientDrawable.Orientation.TL_BR, c).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(2), 0x55FFFFFF)
    }

    private fun pill(label: String, color: Int, onClick: () -> Unit) = TextView(svc).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
        setOnClickListener { onClick() }
    }

    private fun weightLp(end: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = end }
}
