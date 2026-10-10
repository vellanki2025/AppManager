package com.lucid.appstore.download.run

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lucid.appstore.download.DownloadComponent
import kotlinx.coroutines.launch

/** Cancel action from the notifications (FR-22). */
class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val c = DownloadComponent.get()
        c.scope.launch {
            try { c.coordinator.cancelCurrentRun() } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION = "com.lucid.appstore.download.CANCEL"
        fun intent(context: Context): Intent = Intent(context, CancelReceiver::class.java).setAction(ACTION)
    }
}
