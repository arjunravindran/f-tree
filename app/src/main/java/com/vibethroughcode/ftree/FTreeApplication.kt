package com.vibethroughcode.ftree

import android.app.Application

class FTreeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // An alarm does not survive being force-stopped, so every start puts the morning's back.
        // With reminders off this reads one preference and builds nothing else.
        if (container.reminderPreferences.enabled.value) container.reminders.ensure()
        // Sync is opt-in: with the switch off this reads one preference and opens nothing.
        if (container.syncPreferences.enabled.value) container.syncService
    }
}
