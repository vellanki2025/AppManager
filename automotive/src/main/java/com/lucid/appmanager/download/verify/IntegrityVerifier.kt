package com.lucid.appmanager.download.verify

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.lucid.appmanager.catalogue.Manifest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface VerifyResult {
    data object Ok : VerifyResult
    data class Failed(val reason: Reason, val detail: String) : VerifyResult

    enum class Reason { LENGTH, NOT_AN_APK, DIGEST, SIGNATURE }
}

/**
 * Gate between a finished download and an install (FR-39 … FR-41).
 *
 * Checks run cheapest-first — length, APK parse, digest, signing certificate — so a truncated file is
 * rejected before anything hashes several hundred megabytes of it.
 *
 * The signing check (FR-40) is the one that matters most: a digest only proves the bytes arrived intact,
 * not that they are legitimate. A privileged store holding INSTALL_PACKAGES must not install an APK
 * signed by someone else.
 */
class IntegrityVerifier(
    private val packages: PackageManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun verify(apk: File, manifest: Manifest): VerifyResult = withContext(ioDispatcher) {
        if (!apk.exists()) return@withContext VerifyResult.Failed(VerifyResult.Reason.LENGTH, "file missing")

        if (manifest.sizeBytes > 0 && apk.length() != manifest.sizeBytes) {
            return@withContext VerifyResult.Failed(
                VerifyResult.Reason.LENGTH,
                "expected ${manifest.sizeBytes} bytes, got ${apk.length()}",
            )
        }

        val info = packages.getPackageArchiveInfo(apk.absolutePath, SIGNING_FLAGS)
            ?: return@withContext VerifyResult.Failed(VerifyResult.Reason.NOT_AN_APK, "not a parsable APK")

        if (info.packageName != manifest.packageName) {
            return@withContext VerifyResult.Failed(
                VerifyResult.Reason.NOT_AN_APK,
                "APK is ${info.packageName}, manifest is ${manifest.packageName}",
            )
        }

        if (manifest.digest.value.isNotBlank()) {
            val actual = digest(apk, manifest.digest.algorithm)
            if (!manifest.digest.matches(actual)) {
                return@withContext VerifyResult.Failed(
                    VerifyResult.Reason.DIGEST,
                    "${manifest.digest.algorithm} mismatch: expected ${manifest.digest.value}, got $actual",
                )
            }
        }

        verifySigner(apk, manifest.packageName)
    }

    /**
     * Compares the downloaded APK's signing certificates with the installed package's.
     *
     * On a first install there is nothing to compare against; that is allowed and the caller logs it. The
     * trust then rests on the authenticated catalogue rather than on continuity of signing.
     */
    private fun verifySigner(apk: File, packageName: String): VerifyResult {
        val installed = try {
            packages.getPackageInfo(packageName, SIGNING_FLAGS)
        } catch (e: PackageManager.NameNotFoundException) {
            return VerifyResult.Ok              // first install, nothing to compare
        }

        val installedCerts = installed.signatureDigests()
        if (installedCerts.isEmpty()) return VerifyResult.Ok

        val archive = packages.getPackageArchiveInfo(apk.absolutePath, SIGNING_FLAGS)
            ?: return VerifyResult.Failed(VerifyResult.Reason.NOT_AN_APK, "signing info unreadable")
        val archiveCerts = archive.signatureDigests()

        return if (archiveCerts.isNotEmpty() && archiveCerts.intersect(installedCerts).isNotEmpty()) {
            VerifyResult.Ok
        } else {
            VerifyResult.Failed(
                VerifyResult.Reason.SIGNATURE,
                "APK is signed by a different certificate than the installed $packageName",
            )
        }
    }

    private suspend fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                coroutineContext.ensureActive()
                val read = stream.read(buffer)
                if (read == -1) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        @Suppress("DEPRECATION")
        val SIGNING_FLAGS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES

        @Suppress("DEPRECATION")
        fun android.content.pm.PackageInfo.signatureDigests(): Set<String> {
            val signatures: Array<out Signature> = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }.orEmpty()
                else -> this.signatures.orEmpty()
            }
            val md = MessageDigest.getInstance("SHA-256")
            return signatures.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        }
    }
}
