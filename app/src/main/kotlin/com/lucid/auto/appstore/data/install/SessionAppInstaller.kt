package com.lucid.auto.appstore.data.install

import android.Manifest
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Installs APKs with the platform [PackageInstaller] session API.
 *
 * ### Dual mode (FR-5.4)
 *
 * The same code path serves both builds; only [installMode] differs, and it is probed at runtime:
 *
 * - **Privileged system build** — `INSTALL_PACKAGES` is granted, so the commit completes with no
 *   dialog at all, for both new installs and updates.
 * - **Ordinary sideloaded build** — the commit reports `STATUS_PENDING_USER_ACTION` and
 *   [InstallResultReceiver] launches the platform confirmation activity. On API 31+ the session
 *   additionally requests `USER_ACTION_NOT_REQUIRED`, which the platform honours for updates to
 *   packages this store originally installed — that is what makes auto-update demonstrable on a
 *   stock emulator.
 */
class SessionAppInstaller(
    private val context: Context,
) : AppInstaller {

    override val installMode: InstallMode
        get() = if (hasSilentInstallPermission()) InstallMode.SILENT else InstallMode.INTERACTIVE

    private fun hasSilentInstallPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun install(packageName: String, apk: File): InstallOutcome =
        withContext(Dispatchers.IO) {
            if (!apk.exists() || apk.length() == 0L) {
                return@withContext InstallOutcome.Failure(-1, "APK file is missing or empty")
            }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Ask the platform to skip the confirmation dialog. Granted outright in the
                    // privileged build; on an ordinary build the platform honours it for updates
                    // to packages we installed ourselves, and otherwise falls back to a dialog.
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInstallLocation(PackageInfoInstallLocationAuto)
                }
            }

            var sessionId = -1
            try {
                sessionId = installer.createSession(params)
                val deferred = InstallResultReceiver.awaitSession(sessionId)

                installer.openSession(sessionId).use { session ->
                    session.openWrite(APK_ENTRY_NAME, 0, apk.length()).use { output ->
                        apk.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                        session.fsync(output)
                    }
                    session.commit(
                        InstallResultReceiver.pendingIntentFor(context, sessionId).intentSender,
                    )
                }

                withTimeoutOrNull(INSTALL_TIMEOUT_MS) { deferred.await() }
                    ?: InstallOutcome.Failure(-1, "Timed out waiting for the installer")
            } catch (e: SecurityException) {
                Log.e(TAG, "Install of $packageName was refused", e)
                InstallOutcome.Failure(-1, "Install refused: ${e.message ?: "permission denied"}")
            } catch (e: Exception) {
                Log.e(TAG, "Install of $packageName failed", e)
                InstallOutcome.Failure(-1, "Install failed: ${e.message ?: e::class.java.simpleName}")
            } finally {
                if (sessionId != -1) {
                    InstallResultReceiver.forget(sessionId)
                }
                // The staged copy in the session is the authoritative one now; drop our cache copy.
                apk.delete()
            }
        }

    private companion object {
        const val TAG = "SessionAppInstaller"
        const val APK_ENTRY_NAME = "lucid_appstore.apk"
        const val INSTALL_TIMEOUT_MS = 5 * 60 * 1000L
        /** PackageInfo.INSTALL_LOCATION_AUTO */
        const val PackageInfoInstallLocationAuto = 0
    }
}
