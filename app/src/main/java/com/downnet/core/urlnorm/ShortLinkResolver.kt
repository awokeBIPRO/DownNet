package com.downnet.core.urlnorm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Unwraps shortener links (t.co & friends) by following redirects manually —
 * HEAD requests, no auto-follow, at most [maxHops] hops. Any failure returns
 * the input unchanged: an unresolved t.co link is still better handled by the
 * extractor than by an error screen.
 */
class ShortLinkResolver(private val client: OkHttpClient) {

    suspend fun resolve(url: String, maxHops: Int = 5): String = withContext(Dispatchers.IO) {
        var current = url
        repeat(maxHops) {
            if (!isShortener(current)) return@withContext current
            current = resolveOne(current) ?: return@withContext current
        }
        current
    }

    private fun resolveOne(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "DownNet/0.1 (link unwrapper)")
            .build()
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (response.isRedirect) {
                    response.header("Location")?.let { location ->
                        // Shorteners sometimes answer with a relative path.
                        if (location.startsWith("http")) location
                        else java.net.URI(url).resolve(location).toString()
                    }
                } else {
                    null
                }
            }
    }.getOrNull()
}
