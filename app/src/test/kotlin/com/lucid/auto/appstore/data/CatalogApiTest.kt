package com.lucid.auto.appstore.data

import com.google.common.truth.Truth.assertThat
import com.lucid.auto.appstore.data.catalog.HttpCatalogApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Exercises the real HTTP client and the real parser against a stub server. */
class CatalogApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HttpCatalogApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = HttpCatalogApi(OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun `parses the catalog and hits the documented path`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "manifestVersion": 1,
                  "generatedAt": "2026-08-28T10:00:00Z",
                  "apps": [
                    {
                      "packageName": "com.lucid.auto.media",
                      "title": "Lucid Media",
                      "versionName": "1.2.0",
                      "versionCode": 10200,
                      "apkUrl": "http://host/apks/media.apk",
                      "iconUrl": "http://host/icons/media.png",
                      "sizeBytes": 4194304,
                      "sha256": "abc123",
                      "releaseNotes": "Gapless playback."
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val catalog = api.fetchCatalog(baseUrl())

        assertThat(server.takeRequest().path).isEqualTo("/api/v1/apps")
        assertThat(catalog.apps).hasSize(1)
        with(catalog.apps.single()) {
            assertThat(packageName).isEqualTo("com.lucid.auto.media")
            assertThat(versionCode).isEqualTo(10200L)
            assertThat(sha256).isEqualTo("abc123")
            assertThat(releaseNotes).isEqualTo("Gapless playback.")
        }
    }

    @Test
    fun `unknown fields do not break parsing`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "manifestVersion": 2,
                  "somethingNewFromTheServer": {"nested": true},
                  "apps": [{
                    "packageName": "com.spotify.music",
                    "title": "Spotify",
                    "versionName": "8.9.60",
                    "versionCode": 89060000,
                    "apkUrl": "http://host/a.apk",
                    "rolloutPercentage": 25
                  }]
                }
                """.trimIndent(),
            ),
        )

        val catalog = api.fetchCatalog(baseUrl())

        assertThat(catalog.manifestVersion).isEqualTo(2)
        assertThat(catalog.apps.single().packageName).isEqualTo("com.spotify.music")
        assertThat(catalog.apps.single().sha256).isNull()
    }

    @Test(expected = IOException::class)
    fun `a server error is reported as an IOException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        api.fetchCatalog(baseUrl())
    }

    @Test
    fun `a trailing slash on the base url does not double up the path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"apps":[]}"""))

        api.fetchCatalog(server.url("/").toString()) // ends with a slash

        assertThat(server.takeRequest().path).isEqualTo("/api/v1/apps")
    }
}
