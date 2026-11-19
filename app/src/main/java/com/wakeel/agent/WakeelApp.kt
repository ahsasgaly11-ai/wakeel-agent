package com.wakeel.agent

import android.app.Application
import com.wakeel.agent.agent.AgentEngine
import com.wakeel.agent.agent.AppIndex
import com.wakeel.agent.agent.AppMapStore
import com.wakeel.agent.agent.AppNotes
import com.wakeel.agent.agent.DemoRecorder
import com.wakeel.agent.agent.HistoryStore
import com.wakeel.agent.agent.SkillStore
import com.wakeel.agent.core.Settings

class WakeelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }
}

/** Single place that owns the app-wide singletons. */
object Hub {
    lateinit var settings: Settings
        private set
    lateinit var history: HistoryStore
        private set
    lateinit var skills: SkillStore
        private set
    lateinit var notes: AppNotes
        private set
    lateinit var maps: AppMapStore
        private set
    lateinit var apps: AppIndex
        private set
    lateinit var recorder: DemoRecorder
        private set
    lateinit var engine: AgentEngine
        private set

    fun init(app: Application) {
        settings = Settings(app)
        history = HistoryStore(app)
        skills = SkillStore(app)
        notes = AppNotes(app)
        maps = AppMapStore(app)
        apps = AppIndex(app)
        recorder = DemoRecorder(app, settings, skills, apps)
        engine = AgentEngine(app, settings, history, skills, notes, apps, maps) { recorder.active }
    }
}
