package com.lucid.auto.appstore.data.download

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the catalogue icon for an app that is not installed yet (an installed app's icon comes
 * from PackageManager instead). Kept tiny and dependency-free on purpose — no image library is
 * warranted for three 96px PNGs.
 */
interface RemoteIconLoader {
    suspend fun load(url: String): ImageBitmap?
}

class HttpRemoteIconLoader(
    private val client: OkHttpClient,
) : RemoteIconLoader {

    override suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }.getOrNull()
    }
}
