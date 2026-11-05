package com.lucid.appmanager.download.events

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lucid.appmanager.download.policy.NetworkClassifier

/**
 * Writes `[runId] EVENT | batt=NN% chg=Y net=CLASS drv=STATE bytes=A/B | message` to logcat (FR-26)
 * and, when [debugToasts] is on, shows a Toast per non-quiet event (FR-27).
 */
class Logger(private val context: Context, private val debugToasts: Boolean) : EventSink {
    private val main = Handler(Looper.getMainLooper())

    override fun onEvent(event: ServiceEvent) {
        val line = format(event)
        Log.println(event.type.level, TAG, line)
        if (debugToasts && !event.type.quiet) {
            main.post { Toast.makeText(context, "${event.type} ${event.message}".trim(), Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        const val TAG = "DlService"

        fun format(e: ServiceEvent): String {
            val d = e.device
            val batt = d?.let { "batt=${it.battery.levelPercent}% chg=${if (it.battery.charging) "Y" else "N"}" } ?: "batt=?"
            val net = "net=" + (d?.let { NetworkClassifier.classify(it.network) } ?: "?")
            val drv = "drv=" + (d?.driving ?: "?")
            val bytes = "bytes=" + (e.progress?.let { "${it.downloaded}/${it.total ?: "?"}" } ?: "-")
            return "[${e.runId}] ${e.type} | $batt $net $drv $bytes | ${e.message}"
        }
    }
}
