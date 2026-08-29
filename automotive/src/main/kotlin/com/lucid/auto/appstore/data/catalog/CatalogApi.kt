package com.lucid.auto.appstore.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Fetches and parses the remote catalogue. */
interface CatalogApi {
    suspend fun fetchCatalog(baseUrl: String): AppCatalog
}

class HttpCatalogApi(
    private val client: OkHttpClient,
    private val json: Json = DEFAULT_JSON,
) : CatalogApi {

    override suspend fun fetchCatalog(baseUrl: String): AppCatalog = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}$CATALOG_PATH"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Catalog request failed: HTTP ${response.code} for $url")
            }
            val body = response.body?.string()
                ?: throw IOException("Catalog response had an empty body")
            json.decodeFromString(AppCatalog.serializer(), body)
        }
    }

    companion object {
        const val CATALOG_PATH = "/api/v1/apps"
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
