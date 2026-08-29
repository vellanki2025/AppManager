package com.lucid.auto.appstore

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.lucid.auto.appstore.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class LucidAppStoreApplication : Application(), Configuration.Provider {

    /**
     * Safety net: if androidx.startup's InitializationProvider has not run yet, WorkManager
     * initialises on demand from this configuration instead of throwing.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()


    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val graph = ServiceLocator.from(this)

        // Keep the periodic worker in lockstep with the flag, wherever the flag is changed from.
        graph.settingsRepository.autoUpdateEnabled
            .distinctUntilChanged()
            .onEach { enabled -> graph.autoUpdateScheduler.applyFlag(enabled) }
            .launchIn(appScope)
    }
}
