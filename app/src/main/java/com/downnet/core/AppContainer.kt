package com.downnet.core

import android.content.Context
import com.downnet.core.urlnorm.ShortLinkResolver
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual DI container. Small app, one owner — a framework would only add a
 * version matrix to keep green. Grow into Hilt only if this stops being
 * trivially navigable.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /** Shared client for API-shaped traffic (short-link unwrapping, IG APIs). */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val shortLinkResolver: ShortLinkResolver by lazy { ShortLinkResolver(httpClient) }
}
