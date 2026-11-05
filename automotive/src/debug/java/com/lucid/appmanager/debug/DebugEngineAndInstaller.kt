package com.lucid.appmanager.debug

import com.lucid.appmanager.catalogue.Artifact
import com.lucid.appmanager.download.engine.DownloadEngine
import com.lucid.appmanager.download.engine.DownloadRequest
import com.lucid.appmanager.download.engine.DownloadResult
import com.lucid.appmanager.install.InstallMode
import com.lucid.appmanager.install.InstallOutcome
import com.lucid.appmanager.install.Installer
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/**
 * Wraps the real engine so a test can inject the failures the retry ladder and the integrity checks are
 * supposed to survive. Debug builds only.
 *
 * When a local APK is set it short-circuits the transfer entirely: the file is copied into place and the
 * run proceeds to verification and install. That is what makes the install paths testable without a
 * backend.
 */
class DebugDownloadEngine(
    private val request: DownloadRequest,
    private val delegate: DownloadEngine,
) : DownloadEngine {

    override suspend fun download(onProgress: (Long, Long?) -> Unit): DownloadResult {
        DebugState.forcedHttpStatus?.let { code ->
            DebugState.forcedHttpStatus = null
            return if (code == 404) DownloadResult.Fatal("debug: forced HTTP $code")
            else DownloadResult.Retryable("debug: forced HTTP $code")
        }

        if (DebugState.transientFailuresLeft > 0) {
            DebugState.transientFailuresLeft--
            return DownloadResult.Retryable("debug: forced transient failure")
        }

        val local = DebugState.localApkPath?.let(::File)
        if (local != null && local.exists()) {
            return copyLocal(local, onProgress)
        }

        val result = delegate.download(onProgress)
        if (result is DownloadResult.Success) damageIfAsked()
        return result
    }

    override suspend fun discard() = delegate.discard()

    /** Stands in for a transfer so install can be driven from an APK already on the device. */
    private suspend fun copyLocal(local: File, onProgress: (Long, Long?) -> Unit): DownloadResult {
        request.target.parentFile?.mkdirs()
        val total = local.length()
        val perTick = (DebugState.speedKbps?.toLong()?.times(1024L) ?: total).coerceAtLeast(1L)
        var written = 0L
        try {
            local.inputStream().use { input ->
                request.target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                        if (DebugState.speedKbps != null) delay(1000L * read / perTick)
                    }
                }
            }
        } catch (e: IOException) {
            return DownloadResult.Retryable("debug: local copy failed: ${e.message}")
        }
        damageIfAsked()
        return DownloadResult.Success
    }

    /** Corruption and truncation after a successful transfer, so [IntegrityVerifier] is what rejects it. */
    private fun damageIfAsked() {
        val file = request.target
        if (DebugState.corruptNextDownload && file.exists()) {
            DebugState.corruptNextDownload = false
            file.appendBytes("debug-corruption".toByteArray())
        }
        if (DebugState.truncateNextDownloadBy > 0 && file.exists()) {
            val by = DebugState.truncateNextDownloadBy.toLong()
            DebugState.truncateNextDownloadBy = 0
            java.io.RandomAccessFile(file, "rw").use { it.setLength((file.length() - by).coerceAtLeast(0L)) }
        }
    }
}

/** Forces [InstallOutcome.Failed] on demand so FR-44's re-read path can be exercised. Debug builds only. */
class DebugInstaller(private val delegate: Installer) : Installer {
    override val mode: InstallMode get() = delegate.mode

    override suspend fun install(artifact: Artifact, apk: File): InstallOutcome {
        DebugState.forcedInstallFailureCode?.let { code ->
            DebugState.forcedInstallFailureCode = null
            apk.delete()
            return InstallOutcome.Failed(code, "debug: forced install failure")
        }
        return delegate.install(artifact, apk)
    }
}
