package com.lucid.auto.appstore.data.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives the outcome of a [PackageInstaller] session commit.
 *
 * The platform hands results back through a PendingIntent rather than a return value, so the
 * installer parks a [CompletableDeferred] here keyed by session id and completes it when the
 * broadcast lands.
 *
 * When the status is [PackageInstaller.STATUS_PENDING_USER_ACTION] — the unprivileged path —
 * the broadcast carries a confirmation Intent that must be launched. Launching it from a
 * receiver requires FLAG_ACTIVITY_NEW_TASK.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = extractConfirmIntent(intent)
                if (confirmIntent == null) {
                    Log.w(TAG, "PENDING_USER_ACTION with no confirmation intent for $sessionId")
                    complete(sessionId, InstallOutcome.Failure(status, "Missing confirmation intent"))
                    return
                }
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmIntent) }
                    .onFailure {
                        Log.e(TAG, "Could not show install confirmation", it)
                        complete(sessionId, InstallOutcome.Failure(status, "Could not show install dialog"))
                    }
                // The real outcome arrives as a second broadcast once the user answers, so we
                // deliberately leave the deferred pending here.
            }

            PackageInstaller.STATUS_SUCCESS ->
                complete(sessionId, InstallOutcome.Success)

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                complete(sessionId, InstallOutcome.Cancelled)

            else ->
                complete(sessionId, InstallOutcome.Failure(status, describe(status, message)))
        }
    }

    @Suppress("DEPRECATION")
    private fun extractConfirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val TAG = "InstallResultReceiver"
        const val ACTION_INSTALL_RESULT = "com.lucid.auto.appstore.INSTALL_RESULT"

        private val pending = ConcurrentHashMap<Int, CompletableDeferred<InstallOutcome>>()

        /** Registers a waiter for [sessionId] before the session is committed. */
        fun awaitSession(sessionId: Int): CompletableDeferred<InstallOutcome> =
            CompletableDeferred<InstallOutcome>().also { pending[sessionId] = it }

        fun forget(sessionId: Int) {
            pending.remove(sessionId)
        }

        private fun complete(sessionId: Int, outcome: InstallOutcome) {
            pending.remove(sessionId)?.complete(outcome)
        }

        fun pendingIntentFor(context: Context, sessionId: Int): PendingIntent {
            val intent = Intent(ACTION_INSTALL_RESULT)
                .setPackage(context.packageName)
                .setClass(context, InstallResultReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }

        fun describe(status: Int, message: String): String {
            val label = when (status) {
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked by the device policy or user"
                PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicts with an existing package (signature or downgrade)"
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Incompatible with this device"
                PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is invalid or corrupt"
                PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
                PackageInstaller.STATUS_FAILURE_TIMEOUT -> "The install timed out"
                PackageInstaller.STATUS_FAILURE -> "Install failed"
                else -> "Install failed (status $status)"
            }
            return if (message.isBlank()) label else "$label: $message"
        }
    }
}
