package com.wakeel.agent

import android.app.Application
import com.wakeel.agent.agent.AgentEngine
import com.wakeel.agent.agent.AppIndex
import com.wakeel.agent.agent.HistoryStore
import com.wakeel.agent.agent.MacroStore
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
    lateinit var macros: MacroStore
        private set
    lateinit var apps: AppIndex
        private set
    lateinit var engine: AgentEngine
        private set

    fun init(app: Application) {
        settings = Settings(app)
        history = HistoryStore(app)
        macros = MacroStore(app)
        apps = AppIndex(app)
        engine = AgentEngine(app, settings, history, macros, apps)
    }
}
