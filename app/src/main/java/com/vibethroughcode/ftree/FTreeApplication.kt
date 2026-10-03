package com.vibethroughcode.ftree

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.vibethroughcode.ftree.data.CatalogStore
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.data.TreeInfo
import java.io.File

class FTreeApplication : Application() {
    lateinit var catalog: TreeCatalog
        private set

    /** The open tree's wiring. Replaced as a whole when another tree is opened. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("tree-catalog", Context.MODE_PRIVATE)
        catalog = TreeCatalog(
            object : CatalogStore {
                override fun read(): String? = prefs.getString("catalog", null)
                override fun write(text: String) { prefs.edit().putString("catalog", text).apply() }
            },
        )
        open(catalog.active)
    }

    private fun open(tree: TreeInfo) {
        container = AppContainer(this, tree)
        // An alarm does not survive being force-stopped, so every start puts the morning's back.
        // With reminders off this reads one preference and builds nothing else.
        if (container.reminderPreferences.enabled.value) container.reminders.ensure()
        // Sync is opt-in: with the switch off this reads one preference and opens nothing.
        if (container.syncPreferences.enabled.value) container.syncService
    }

    /**
     * Opens another tree: remembers the choice, shuts the current wiring down and builds the new
     * one. Screens and view models still hold the old tree, so the caller must restart the UI
     * ([relaunch]) straight after.
     */
    fun switchTo(id: String): Boolean {
        if (id == container.tree.id) return true
        if (!catalog.setActive(id)) return false
        val old = container
        old.stopServices()
        open(catalog.active)
        Handler(Looper.getMainLooper()).postDelayed({ old.closeDatabase() }, CLOSE_DELAY_MS)
        return true
    }

    /** A fresh task for the main screen, so no view model of the previous tree survives. */
    fun relaunch() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    private companion object {
        const val CLOSE_DELAY_MS = 3_000L
    }

    /** Deletes what a removed tree left behind: its database, photographs and identity file. */
    fun deleteFilesOf(tree: TreeInfo) {
        deleteDatabase(tree.databaseName)
        File(filesDir, tree.photoDirectory).deleteRecursively()
        deleteSharedPreferences(tree.identityFile)
    }
}
