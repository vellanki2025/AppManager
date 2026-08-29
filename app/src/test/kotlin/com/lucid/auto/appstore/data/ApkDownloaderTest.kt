package com.lucid.auto.appstore.data

import com.google.common.truth.Truth.assertThat
import com.lucid.auto.appstore.data.download.DownloadResult
import com.lucid.auto.appstore.data.download.HttpApkDownloader
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ApkDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: HttpApkDownloader
    private lateinit var cacheDir: java.io.File

    private val payload = "PK pretend this is an apk".toByteArray()
    private val payloadSha = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        cacheDir = temp.newFolder("apks")
        downloader = HttpApkDownloader(OkHttpClient(), cacheDir)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueueApk() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
    }

    @Test
    fun `downloads to cache and reports progress`() = runTest {
        enqueueApk()
        val progress = mutableListOf<Int>()

        val result = downloader.download(
            server.url("/apks/media-1.2.0.apk").toString(),
            expectedSha256 = null,
        ) { progress += it }

        assertThat(result).isInstanceOf(DownloadResult.Success::class.java)
        val file = (result as DownloadResult.Success).file
        assertThat(file.readBytes()).isEqualTo(payload)
        assertThat(file.name).isEqualTo("media-1.2.0.apk")
        assertThat(progress).isNotEmpty()
        assertThat(progress.last()).isEqualTo(100)
    }

    @Test
    fun `a matching sha256 is accepted`() = runTest {
        enqueueApk()
        val result = downloader.download(server.url("/a.apk").toString(), payloadSha)
        assertThat(result).isInstanceOf(DownloadResult.Success::class.java)
    }

    @Test
    fun `a mismatched sha256 is rejected and the partial file is deleted`() = runTest {
        enqueueApk()

        val result = downloader.download(server.url("/a.apk").toString(), "deadbeef")

        assertThat(result).isInstanceOf(DownloadResult.Failure::class.java)
        assertThat((result as DownloadResult.Failure).reason).contains("Integrity check failed")
        assertThat(cacheDir.walkTopDown().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun `an http error is reported as a failure, not a crash`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = downloader.download(server.url("/missing.apk").toString(), null)

        assertThat(result).isInstanceOf(DownloadResult.Failure::class.java)
        assertThat((result as DownloadResult.Failure).reason).contains("404")
    }

    @Test
    fun `an empty body is rejected`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val result = downloader.download(server.url("/empty.apk").toString(), null)

        assertThat(result).isInstanceOf(DownloadResult.Failure::class.java)
    }

    @Test
    fun `a traversal-shaped filename cannot escape the cache directory`() = runTest {
        enqueueApk()

        val result = downloader.download(server.url("/apks/dots..slash..evil.apk").toString(), null)

        val file = (result as DownloadResult.Success).file
        assertThat(file.canonicalPath).startsWith(cacheDir.canonicalPath)
        assertThat(file.parentFile!!.canonicalPath).isEqualTo(cacheDir.canonicalPath)
    }
}
