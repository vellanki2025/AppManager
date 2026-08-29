package com.lucid.auto.appstore.fakes

import androidx.compose.ui.graphics.ImageBitmap
import com.lucid.auto.appstore.data.catalog.AppCatalog
import com.lucid.auto.appstore.data.catalog.CatalogApi
import com.lucid.auto.appstore.data.catalog.RemoteApp
import com.lucid.auto.appstore.data.download.ApkDownloader
import com.lucid.auto.appstore.data.download.DownloadResult
import com.lucid.auto.appstore.data.download.RemoteIconLoader
import com.lucid.auto.appstore.data.install.AppInstaller
import com.lucid.auto.appstore.data.install.InstallMode
import com.lucid.auto.appstore.data.install.InstallOutcome
import com.lucid.auto.appstore.data.local.InstalledApp
import com.lucid.auto.appstore.data.local.PackageInspector
import com.lucid.auto.appstore.data.settings.SettingsRepository
import com.lucid.auto.appstore.update.AutoUpdateScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** An in-memory stand-in for PackageManager. */
class FakePackageInspector(
    private val installed: MutableMap<String, InstalledApp> = mutableMapOf(),
) : PackageInspector {

    var iconsAvailable: Boolean = false

    override fun getInstalled(packageName: String): InstalledApp? = installed[packageName]

    override fun getIcon(packageName: String): ImageBitmap? =
        if (iconsAvailable && installed.containsKey(packageName)) ImageBitmap(1, 1) else null

    fun put(packageName: String, versionName: String, versionCode: Long, label: String? = null) {
        installed[packageName] = InstalledApp(packageName, label, versionName, versionCode)
    }

    fun remove(packageName: String) {
        installed.remove(packageName)
    }
}

class FakeCatalogApi(
    var catalog: AppCatalog = AppCatalog(),
    var error: Throwable? = null,
) : CatalogApi {
    var fetchCount = 0
    override suspend fun fetchCatalog(baseUrl: String): AppCatalog {
        fetchCount++
        error?.let { throw it }
        return catalog
    }
}

class FakeApkDownloader(
    private val tempDir: File,
    var result: ((RemoteApp?) -> DownloadResult)? = null,
) : ApkDownloader {
    val downloadedUrls = mutableListOf<String>()
    var forcedFailure: String? = null

    override suspend fun download(
        url: String,
        expectedSha256: String?,
        onProgress: (Int) -> Unit,
    ): DownloadResult {
        downloadedUrls += url
        forcedFailure?.let { return DownloadResult.Failure(it) }
        onProgress(50)
        onProgress(100)
        val file = File(tempDir, url.substringAfterLast('/')).apply {
            parentFile?.mkdirs()
            writeText("fake-apk")
        }
        return DownloadResult.Success(file)
    }
}

class FakeAppInstaller(
    override val installMode: InstallMode = InstallMode.INTERACTIVE,
) : AppInstaller {
    val installedPackages = mutableListOf<String>()
    var outcome: InstallOutcome = InstallOutcome.Success
    /** Called after a successful install so a test can mutate the fake "device" state. */
    var onSuccess: (String) -> Unit = {}

    override suspend fun install(packageName: String, apk: File): InstallOutcome {
        installedPackages += packageName
        if (outcome is InstallOutcome.Success) onSuccess(packageName)
        return outcome
    }
}

class FakeSettingsRepository(
    autoUpdate: Boolean = false,
    baseUrl: String = "http://localhost:8080",
) : SettingsRepository {
    private val auto = MutableStateFlow(autoUpdate)
    private val url = MutableStateFlow(baseUrl)

    override val autoUpdateEnabled: Flow<Boolean> = auto
    override val catalogBaseUrl: Flow<String> = url

    override suspend fun setAutoUpdateEnabled(enabled: Boolean) { auto.value = enabled }
    override suspend fun setCatalogBaseUrl(url: String) { this.url.value = url }
}

class FakeAutoUpdateScheduler : AutoUpdateScheduler {
    var enqueueCount = 0
    var cancelCount = 0
    val calls = mutableListOf<String>()

    override fun enqueue() { enqueueCount++; calls += "enqueue" }
    override fun cancel() { cancelCount++; calls += "cancel" }
}

class FakeRemoteIconLoader(private val icon: ImageBitmap? = null) : RemoteIconLoader {
    val requestedUrls = mutableListOf<String>()
    override suspend fun load(url: String): ImageBitmap? {
        requestedUrls += url
        return icon
    }
}

/** Convenience builder for catalogue entries. */
fun remoteApp(
    packageName: String,
    versionName: String,
    versionCode: Long,
    title: String = packageName,
    sha256: String? = null,
) = RemoteApp(
    packageName = packageName,
    title = title,
    versionName = versionName,
    versionCode = versionCode,
    apkUrl = "http://localhost:8080/apks/$packageName-$versionName.apk",
    iconUrl = "http://localhost:8080/icons/$packageName.png",
    sizeBytes = 1024,
    sha256 = sha256,
)
