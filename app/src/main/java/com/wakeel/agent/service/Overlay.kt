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
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.wakeel.agent.Hub
import com.wakeel.agent.R
import com.wakeel.agent.agent.DemoRecorder
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

    // Learning by demonstration
    private var rec = DemoRecorder.State()
    private var recStatusTv: TextView? = null

    // ---------- Colors ----------
    private val indigo = intArrayOf(0xFF2FCF9A.toInt(), 0xFF0B6B55.toInt())   // idle: emerald
    private val teal = intArrayOf(0xFF6FA8FF.toInt(), 0xFF2F5FB3.toInt())     // working: sapphire
    private val amber = intArrayOf(0xFFE8C36A.toInt(), 0xFFB8862B.toInt())    // needs you: gold
    private val ruby = intArrayOf(0xFFFF6B6B.toInt(), 0xFFB3261E.toInt())     // recording your steps
    private val bold: Typeface = runCatching { ResourcesCompat.getFont(svc, R.font.tajawal_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val regular: Typeface = runCatching { ResourcesCompat.getFont(svc, R.font.tajawal_medium) }.getOrNull() ?: Typeface.DEFAULT

    init {
        // The bubble is the only way to end a recording, so it follows the recorder even when the owner hid it
        scope.launch { Hub.recorder.state.collect { onRecording(it) } }
    }

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
                            rec.active -> openRecordingPanel()
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
                setColor(0xF50A1512.toInt())
                setStroke(dp(1), 0x553DD9A4)
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
            typeface = bold
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
            typeface = regular
            setTextColor(0xCCFFFFFF.toInt())
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, dp(10))
        }
        p.addView(statusTv)

        questionTv = TextView(svc).apply {
            textSize = 15f
            setTextColor(0xFFFDE68A.toInt())
            typeface = bold
            visibility = View.GONE
            setPadding(0, 0, 0, dp(10))
        }
        p.addView(questionTv)

        confirmRow = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
            addView(pill("سماح", 0xFF1FA97C.toInt()) { Hub.engine.answerConfirm(true) }, weightLp(end = dp(8)))
            addView(pill("رفض", 0xFFE5484D.toInt()) { Hub.engine.answerConfirm(false) }, weightLp())
        }
        p.addView(confirmRow)

        val actions = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        mainBtn = pill("مهمة جديدة", 0xFF0E8A6A.toInt()) { onMain() }
        stopBtn = pill("إيقاف", 0xFFE5484D.toInt()) { Hub.engine.stop() }
        actions.addView(mainBtn, weightLp(end = dp(8)))
        actions.addView(stopBtn, weightLp())
        p.addView(actions)

        if (!attach(p)) return
        render(lastState)
    }

    /** Places a panel next to the bubble. A touch outside closes it unless the agent is waiting for an answer. */
    private fun attach(p: LinearLayout): Boolean {
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
        if (runCatching { wm.addView(p, lp) }.isFailure) return false
        panel = p
        return true
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        statusTv = null; questionTv = null; confirmRow = null; mainBtn = null; stopBtn = null
        recStatusTv = null
    }

    // ---------- Learning by demonstration ----------
    private fun onRecording(s: DemoRecorder.State) {
        val was = rec.active
        rec = s
        if (s.active != was) closePanel()
        if (s.active && bubble == null) show()
        if (!s.active && was && !Hub.settings.showBubble) hide()
        paintBubble()
        recStatusTv?.text = recordingLine(s)
    }

    private fun recordingLine(s: DemoRecorder.State): String =
        (if (s.steps == 0) "لم تُسجَّل خطوة بعد" else "سُجّلت ${s.steps} خطوة") + "\nآخر ما رأيته: ${s.last}"

    private fun openRecordingPanel() {
        if (panel != null) return
        val p = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xF50A1512.toInt())
                setStroke(dp(1), 0x66FF6B6B)
            }
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(svc).apply {
            text = "●  أتعلّم من خطواتك"
            textSize = 17f
            typeface = bold
            setTextColor(0xFFFF8A80.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(svc).apply {
            text = "✕"
            textSize = 18f
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { closePanel() }
        })
        p.addView(header)
        p.addView(TextView(svc).apply {
            text = "«${rec.task.take(90)}»"
            textSize = 15f
            typeface = bold
            setTextColor(Color.WHITE)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, dp(4))
        })
        recStatusTv = TextView(svc).apply {
            text = recordingLine(rec)
            textSize = 13f
            typeface = regular
            setTextColor(0xCCFFFFFF.toInt())
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(4))
        }
        p.addView(recStatusTv)
        p.addView(TextView(svc).apply {
            text = "نفّذ المهمة كاملة بيدك، ثم اضغط «حفظ المهارة». أغلق هذه النافذة لتكمل."
            textSize = 12f
            typeface = regular
            setTextColor(0x99FFFFFF.toInt())
        })
        val actions = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(pill("حفظ المهارة", 0xFF1FA97C.toInt()) { finishRecording(true) }, weightLp(end = dp(8)))
        actions.addView(pill("إلغاء", 0xFFE5484D.toInt()) { finishRecording(false) }, weightLp())
        p.addView(actions)
        attach(p)
    }

    private fun finishRecording(save: Boolean) {
        val skill = Hub.recorder.stop(save)
        closePanel()
        val msg = when {
            !save -> "أُلغي التسجيل، ولم يُحفظ شيء."
            skill == null -> "لم أرَ أي خطوة، فلم تُحفظ مهارة."
            skill.params.isEmpty() -> "حُفظت المهارة (${skill.steps.size} خطوة). قل: «${skill.example.take(50)}» وأنفّذها دون استهلاك."
            else -> "حُفظت المهارة بمتغيّرات. قل مثلاً: «${skill.example.take(50)}» أو بقيم أخرى."
        }
        Toast.makeText(svc, msg, Toast.LENGTH_LONG).show()
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
        if (bubble == null) return
        paintBubble()
        if (s is EngineState.Confirm && panel == null) openPanel()
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

    /** Emerald when idle, sapphire while working, gold when it needs you, ruby with a step count while you teach it. */
    private fun paintBubble() {
        val b = bubble ?: return
        if (rec.active) {
            b.text = if (rec.steps > 0) rec.steps.toString() else "●"
            b.background = oval(ruby)
            stopSpin()
            return
        }
        b.text = "✦"
        when (lastState) {
            is EngineState.Idle -> { b.background = oval(indigo); stopSpin() }
            is EngineState.Running -> { b.background = oval(teal); startSpin() }
            is EngineState.Confirm, is EngineState.Ask -> { b.background = oval(amber); stopSpin() }
        }
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
        typeface = bold
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
        setOnClickListener { onClick() }
    }

    private fun weightLp(end: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = end }
}
