package com.wakeel.agent.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.wakeel.agent.Hub
import com.wakeel.agent.agent.EngineState
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
    private var input: EditText? = null
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
                        if (panel == null) openPanel() else closePanel()
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

        input = EditText(svc).apply {
            hint = "اكتب المهمة…"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x1AFFFFFF)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        p.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
        mainBtn = pill("تنفيذ", 0xFF6366F1.toInt()) { onMain() }
        stopBtn = pill("إيقاف", 0xFFEF4444.toInt()) { Hub.engine.stop() }
        actions.addView(mainBtn, weightLp(end = dp(8)))
        actions.addView(stopBtn, weightLp())
        p.addView(actions)

        val w = svc.screenSize().first
        val lp = WindowManager.LayoutParams(
            minOf(dp(340), w - dp(24)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ((bubbleLp?.y ?: dp(240)) + dp(64))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
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
        input?.let { svc.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        statusTv = null; questionTv = null; input = null; confirmRow = null; mainBtn = null; stopBtn = null
    }

    private fun onMain() {
        val text = input?.text?.toString()?.trim().orEmpty()
        when (lastState) {
            is EngineState.Idle -> if (text.isNotEmpty()) {
                input?.setText("")
                closePanel()
                Hub.engine.start(text)
            }
            is EngineState.Ask -> if (text.isNotEmpty()) {
                input?.setText("")
                Hub.engine.answerAsk(text)
                closePanel()
            }
            else -> {}
        }
    }

    // ---------- Rendering ----------
    private fun render(s: EngineState) {
        lastState = s
        val b = bubble ?: return
        when (s) {
            is EngineState.Idle -> { b.background = oval(indigo); stopSpin() }
            is EngineState.Running -> { b.background = oval(teal); startSpin() }
            is EngineState.Confirm, is EngineState.Ask -> {
                b.background = oval(amber); stopSpin()
                if (panel == null) openPanel()
            }
        }
        if (panel == null) return
        val result = Hub.engine.lastResult.value
        statusTv?.text = when (s) {
            is EngineState.Idle -> result?.let { "آخر نتيجة: $it" } ?: "جاهز. اكتب ما تريد أن أفعله."
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
        input?.visibility = if (s is EngineState.Idle || s is EngineState.Ask) View.VISIBLE else View.GONE
        input?.hint = if (s is EngineState.Ask) "اكتب ردّك…" else "اكتب المهمة…"
        mainBtn?.visibility = if (s is EngineState.Idle || s is EngineState.Ask) View.VISIBLE else View.GONE
        mainBtn?.text = if (s is EngineState.Ask) "إرسال الرد" else "تنفيذ"
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
