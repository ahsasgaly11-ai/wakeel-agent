package com.wakeel.agent.agent

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import com.wakeel.agent.Hub
import com.wakeel.agent.core.TaskRecord
import com.wakeel.agent.core.TaskStatus
import com.wakeel.agent.core.Usage
import com.wakeel.agent.service.AgentAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A task waiting for its time. */
data class Scheduled(val id: Long, val task: String, val at: Long)

/**
 * Keeps the tasks that wait for their time and starts each one when it comes.
 *
 * An exact alarm wakes the phone's processor at the time; the task itself needs a screen it can work on,
 * so if the phone is locked then, a sound is played and the task starts as soon as the owner unlocks it.
 * A task that is still waiting two hours after its time is dropped and reported — a message meant for
 * eight in the evening should not go out the next morning.
 */
class Scheduler(
    private val ctx: Context,
    private val history: HistoryStore,
    private val engine: () -> AgentEngine,
    /** True while something else has the screen (the owner is teaching a task). */
    private val busy: () -> Boolean,
) {
    private val file = File(ctx.filesDir, "schedule.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Scheduled>> = _items.asStateFlow()

    /** Tasks whose time has come and that are waiting for a free, unlocked screen. */
    private val waiting = HashSet<Long>()
    private var timer: Job? = null

    private fun load(): List<Scheduled> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o -> Scheduled(o.getLong("id"), o.getString("task"), o.getLong("at")) }
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Scheduled>) {
        _items.value = list.sortedBy { it.at }
        runCatching {
            file.writeText(JSONArray().also { a -> list.forEach { a.put(JSONObject().put("id", it.id).put("task", it.task).put("at", it.at)) } }.toString())
        }
    }

    @Synchronized
    fun add(task: String, at: Long): Scheduled {
        var id = System.currentTimeMillis()
        while (_items.value.any { it.id == id }) id++
        val item = Scheduled(id, task.trim(), at)
        save(_items.value + item)
        arm()
        return item
    }

    @Synchronized
    fun cancel(id: Long) {
        save(_items.value.filterNot { it.id == id })
        arm()
    }

    @Synchronized
    fun clear() {
        save(emptyList())
        arm()
    }

    /** Sets the alarm (and, while the app is awake, a timer) for the next task. Called at start-up too, so a restart loses nothing. */
    @Synchronized
    fun arm() {
        timer?.cancel()
        timer = null
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, ScheduleReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = _items.value.filter { it.id !in waiting }.minByOrNull { it.at }
        if (next == null) {
            runCatching { am?.cancel(pi) }
            return
        }
        val now = System.currentTimeMillis()
        val at = maxOf(next.at, now + 300)
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { runCatching { am?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } }
        timer = scope.launch {
            delay((next.at - now).coerceAtLeast(0))
            checkDue()
        }
    }

    fun onAlarm() = checkDue()

    private fun checkDue() {
        val now = System.currentTimeMillis()
        val due = synchronized(this) { _items.value.filter { it.at <= now + 300 && waiting.add(it.id) } }
        due.forEach { item -> scope.launch { start(item) } }
        arm()
    }

    private fun screenUsable(): Boolean = runCatching {
        ctx.getSystemService(PowerManager::class.java).isInteractive && !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }.getOrDefault(true)

    private fun toast(text: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(AgentAccessibilityService.instance ?: ctx, text, Toast.LENGTH_LONG).show() }
        }
    }

    private suspend fun start(item: Scheduled) {
        try {
            var cued = false
            while (true) {
                if (_items.value.none { it.id == item.id }) return   // cancelled while it waited
                if (System.currentTimeMillis() - item.at > LATE_LIMIT_MS) {
                    cancel(item.id)
                    val now = System.currentTimeMillis()
                    history.add(
                        TaskRecord(
                            now, item.task, now, now, TaskStatus.FAILED, 0, Usage(), 0.0, 0, 0,
                            "فات موعد هذه المهمة المجدولة (${Schedule.clock(item.at)}) بأكثر من ساعتين والهاتف مقفل أو مشغول، فلم أنفّذها. اطلبها من جديد إن شئت.",
                            emptyList(),
                        )
                    )
                    return
                }
                val usable = screenUsable()
                if (usable && AgentAccessibilityService.instance != null && !engine().isRunning && !busy()) {
                    toast("حان موعد المهمة المجدولة: ${item.task.take(60)}")
                    delay(1200)
                    if (_items.value.any { it.id == item.id } && engine().start(item.task, scheduledFor = item.at)) {
                        synchronized(this) { save(_items.value.filterNot { it.id == item.id }) }
                        return
                    }
                } else if (!usable && !cued) {
                    // The time has come and the phone is locked: say so once, then wait for the owner
                    cued = true
                    runCatching { RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() }
                }
                delay(1500)
            }
        } finally {
            synchronized(this) { waiting.remove(item.id) }
        }
    }

    private companion object {
        const val LATE_LIMIT_MS = 2 * 3600_000L
    }
}

/** The alarm set by [Scheduler]: a scheduled task's time has come. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { Hub.scheduler.onAlarm() }
    }
}
