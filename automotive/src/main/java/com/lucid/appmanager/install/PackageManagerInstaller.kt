package com.lucid.appmanager.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.lucid.appmanager.catalogue.Artifact
import com.lucid.appmanager.download.events.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Install path A (FR-45 … FR-47): a [PackageInstaller] session, committed in-process.
 *
 * As a privileged app holding INSTALL_PACKAGES the session completes with no dialog. Where it is not
 * privileged the platform asks for confirmation; that is reported as [InstallOutcome.NeedsUserAction]
 * rather than launched, see the KDoc on that value.
 *
 * The commit result arrives asynchronously through a PendingIntent, so this suspends on a deferred keyed
 * by session id. Whatever it returns, [com.lucid.appmanager.catalogue.ArtifactResolver] re-reads the
 * installed versionCode afterwards: the platform's answer is advisory, the device is authoritative (FR-44).
 */
class PackageManagerInstaller(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : Installer {

    override val mode = InstallMode.PACKAGE_MANAGER

    override suspend fun install(artifact: Artifact, apk: File): InstallOutcome = withContext(ioDispatcher) {
        if (!apk.exists() || apk.length() == 0L) {
            return@withContext InstallOutcome.Failed(-1, "APK missing or empty at ${apk.absolutePath}")
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(artifact.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            val result = InstallResultReceiver.await(sessionId)

            installer.openSession(sessionId).use { session ->
                session.openWrite(ENTRY_NAME, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, BUFFER_BYTES) }
                    session.fsync(out)
                }
                session.commit(InstallResultReceiver.pendingIntent(context, sessionId).intentSender)
            }

            withTimeoutOrNull(timeoutMs) { result.await() }
                ?: InstallOutcome.Failed(-1, "timed out after ${timeoutMs}ms waiting for the installer")
        } catch (e: SecurityException) {
            Log.e(Logger.TAG, "install of ${artifact.packageName} refused", e)
            InstallOutcome.Failed(-1, "refused: ${e.message ?: "permission denied"}")
        } catch (e: Exception) {
            Log.e(Logger.TAG, "install of ${artifact.packageName} failed", e)
            InstallOutcome.Failed(-1, e.message ?: e::class.java.simpleName)
        } finally {
            if (sessionId != -1) InstallResultReceiver.forget(sessionId)
            apk.delete()                               // FR-47: the session holds the authoritative copy
        }
    }

    private companion object {
        const val ENTRY_NAME = "base.apk"
        const val BUFFER_BYTES = 64 * 1024
        const val DEFAULT_TIMEOUT_MS = 5L * 60 * 1000
    }
}

/**
 * Receives the commit outcome of a [PackageInstaller] session.
 *
 * Not exported. A privileged app that can install packages must not let another app drive its installer.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        val outcome = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Installed
            PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallOutcome.NeedsUserAction
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Failed(status, "aborted")
            else -> InstallOutcome.Failed(status, describe(status, message))
        }
        complete(sessionId, outcome)
    }

    companion object {
        const val ACTION = "com.lucid.appmanager.INSTALL_RESULT"

        private val pending = ConcurrentHashMap<Int, CompletableDeferred<InstallOutcome>>()

        /** Register before committing, so a fast result is never missed. */
        fun await(sessionId: Int): CompletableDeferred<InstallOutcome> =
            CompletableDeferred<InstallOutcome>().also { pending[sessionId] = it }

        fun forget(sessionId: Int) {
            pending.remove(sessionId)
        }

        private fun complete(sessionId: Int, outcome: InstallOutcome) {
            pending.remove(sessionId)?.complete(outcome)
        }

        fun pendingIntent(context: Context, sessionId: Int): PendingIntent {
            val intent = Intent(ACTION)
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
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked by policy or user"
                PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflicts with the installed package (signature or downgrade)"
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible with this device"
                PackageInstaller.STATUS_FAILURE_INVALID -> "the APK is invalid"
                PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
                PackageInstaller.STATUS_FAILURE -> "install failed"
                else -> "install failed (status $status)"
            }
            return if (message.isBlank()) label else "$label: $message"
        }
    }
}
