package com.lucid.appmanager.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lucid.appmanager.download.events.Logger
import com.lucid.appmanager.download.model.DrivingState
import com.lucid.appmanager.download.model.NetworkClass
import java.io.File
import java.time.LocalTime

/**
 * adb front end for [DebugFlagActions]. **Debug builds only**, declared solely in the debug manifest.
 *
 * Exported, but gated on `android.permission.DUMP` — which is stricter than it sounds and is what FR-55
 * is really after. A non-exported receiver cannot be reached by adb at all: `am broadcast` runs as the
 * shell uid and the platform drops a cross-uid delivery to a non-exported component silently, printing
 * `result=0` for "enqueued" either way. DUMP is `signature|privileged`, held by the shell and the system
 * and not grantable to a normal app, so adb works and other apps on the device still cannot drive this
 * app's installer.
 *
 * ```
 * adb shell am broadcast -n com.lucid.appmanager/.debug.DebugActionReceiver \
 *     -a com.lucid.appmanager.debug.TRIGGER_NOW
 * ```
 *
 * Results that have something to say are printed to logcat under the service's own tag, so they appear in
 * the same stream as the events they are testing:
 *
 * ```
 * adb logcat -s DlService
 * ```
 */
class DebugActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.removePrefix(PREFIX) ?: return
        val reply: String? = when (action) {
            // 8.1 flags
            "SET_SERVICE_ENABLED" -> { DebugFlagActions.setServiceEnabled(intent.getBooleanExtra("enabled", true)); null }
            "SET_INSTALL_MODE" -> { DebugFlagActions.setInstallMode(DebugFlagActions.parseMode(intent.getStringExtra("mode"))); null }
            "DUMP_FLAGS" -> DebugFlagActions.dumpFlags()

            // 8.2 schedule
            "TRIGGER_NOW" -> { DebugFlagActions.triggerNow(); null }
            "SET_SCHEDULE_TIME" -> {
                intent.getStringExtra("at")?.let { DebugFlagActions.setScheduleTime(LocalTime.parse(it)) }; null
            }
            "CANCEL_RUN" -> { DebugFlagActions.cancelRun(); null }

            // 8.3 policy gate
            "SET_DRIVING" -> { DebugFlagActions.setDrivingState(intent.enumExtra<DrivingState>("state")); null }
            "SET_BATTERY" -> {
                val level = intent.getIntExtra("level", -1)
                if (level < 0) DebugFlagActions.clearBattery()
                else DebugFlagActions.setBattery(level, intent.getBooleanExtra("charging", false))
                null
            }
            "SET_NETWORK" -> { DebugFlagActions.setNetworkClass(intent.enumExtra<NetworkClass>("class")); null }
            "SET_NETWORK_TYPE" -> {
                val type = intent.enumExtra<DebugFlagActions.NetworkType>("type")
                    ?: return log("SET_NETWORK_TYPE needs --es type " +
                        DebugFlagActions.NetworkType.entries.joinToString("|") { it.name })
                DebugFlagActions.setNetworkType(type)
                null
            }
            "SET_CELLULAR_SUBSCRIBED" -> {
                DebugFlagActions.setCellularSubscribed(
                    if (intent.hasExtra("subscribed")) intent.getBooleanExtra("subscribed", false) else null,
                )
                null
            }
            "SET_FREE_SPACE" -> {
                DebugFlagActions.setFreeSpaceMb(if (intent.hasExtra("mb")) intent.getIntExtra("mb", 0) else null); null
            }
            "CLEAR_SIGNAL_OVERRIDES" -> { DebugFlagActions.clearSignalOverrides(); null }

            // 8.4 cloud and URL
            "SET_MANIFEST" -> {
                val pkg = intent.getStringExtra("pkg") ?: return log("SET_MANIFEST needs --es pkg")
                DebugFlagActions.setManifest(
                    packageName = pkg,
                    versionCode = intent.getLongExtra("code", intent.getIntExtra("code", 1).toLong()),
                    sizeBytes = intent.getLongExtra("size", 0L),
                    digestHex = intent.getStringExtra("digest").orEmpty(),
                )
                null
            }
            "CLEAR_MANIFEST" -> { DebugFlagActions.clearManifest(); null }
            "EXPIRE_URL_NOW" -> { DebugFlagActions.expireUrlNow(); null }
            "FAIL_URL_FETCH" -> { DebugFlagActions.failUrlFetch(intent.getIntExtra("times", 1)); null }
            "SET_URL_TTL" -> { DebugFlagActions.setUrlTtlSeconds(intent.getIntExtra("seconds", 1800)); null }

            // 8.5 download
            "SET_RANGE_SUPPORT" -> { DebugFlagActions.setRangeSupport(intent.getBooleanExtra("supported", true)); null }
            "FORCE_HTTP_STATUS" -> { DebugFlagActions.forceHttpStatus(intent.getIntExtra("code", 500)); null }
            "FORCE_TRANSIENT_FAILURES" -> { DebugFlagActions.forceTransientFailures(intent.getIntExtra("times", 1)); null }
            "SET_SPEED" -> {
                DebugFlagActions.setSpeedKbps(if (intent.hasExtra("kbps")) intent.getIntExtra("kbps", 0) else null); null
            }

            // 8.6 integrity
            "CORRUPT_NEXT_DOWNLOAD" -> { DebugFlagActions.corruptNextDownload(); null }
            "TRUNCATE_NEXT_DOWNLOAD" -> { DebugFlagActions.truncateNextDownload(intent.getIntExtra("bytes", 1024)); null }
            "USE_APK" -> { DebugFlagActions.useLocalApk(intent.getStringExtra("path")); null }

            // 8.7 install, path A
            "INSTALL_NOW" -> {
                val path = intent.getStringExtra("path") ?: return log("INSTALL_NOW needs --es path")
                DebugFlagActions.installNow(File(path)); null
            }
            "FORCE_INSTALL_FAILURE" -> { DebugFlagActions.forceInstallFailure(intent.getIntExtra("code", 1)); null }

            // 8.8 install, path B
            "STAGE_OVERRIDE" -> {
                val path = intent.getStringExtra("path") ?: return log("STAGE_OVERRIDE needs --es path")
                val pkg = intent.getStringExtra("pkg") ?: return log("STAGE_OVERRIDE needs --es pkg")
                DebugFlagActions.stageOverride(File(path), pkg, intent.getLongExtra("code", 1L)); null
            }
            "PRINT_OVERRIDE_COMMANDS" -> DebugFlagActions.printOverrideCommands()
            "DUMP_PENDING_OVERRIDE" -> DebugFlagActions.dumpPendingOverride()
            "CHECK_OVERRIDE_APPLIED" -> DebugFlagActions.checkOverrideApplied()
            "CLEAR_PENDING_OVERRIDE" -> { DebugFlagActions.clearPendingOverride(); null }

            // 8.9 state
            "DUMP_STATE" -> DebugFlagActions.dumpState()
            "RESET_ALL" -> { DebugFlagActions.resetAll(); null }

            else -> "unknown debug action: $action"
        }
        log(reply ?: "ok: $action")
    }

    private fun log(message: String) {
        // Multi-line dumps print one line per entry so logcat keeps them readable.
        message.lineSequence().forEach { Log.i(Logger.TAG, "[debug] $it") }
    }

    private inline fun <reified T : Enum<T>> Intent.enumExtra(key: String): T? =
        getStringExtra(key)?.let { value -> enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } }

    private companion object {
        const val PREFIX = "com.lucid.appmanager.debug."
    }
}
