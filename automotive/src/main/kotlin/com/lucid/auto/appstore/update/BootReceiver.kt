package com.lucid.auto.appstore.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lucid.auto.appstore.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms the periodic auto-update job after a reboot — but only when the flag is on (FR-6.5).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = ServiceLocator.from(appContext)
                val enabled = graph.settingsRepository.isAutoUpdateEnabledNow()
                Log.i(TAG, "Boot completed; auto-update enabled = $enabled")
                graph.autoUpdateScheduler.applyFlag(enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-arm auto-update after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
