package com.kolktech.kahawai

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.kolktech.kahawai.data.auth.ServerConfigStore
import com.kolktech.kahawai.data.auth.TokenStore
import com.kolktech.kahawai.data.network.ApiClient
import com.kolktech.kahawai.data.settings.AppSettingsStore

class KahawaiApp : Application(), SingletonImageLoader.Factory {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var serverConfigStore: ServerConfigStore
        private set
    lateinit var appSettingsStore: AppSettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        serverConfigStore = ServerConfigStore(this)
        appSettingsStore = AppSettingsStore(this)
        ApiClient.init(tokenStore, serverConfigStore)
    }

    /// Artwork (GET /api/v1/catalogue/libraries/{id}/items/{item}/artwork)
    /// requires the same bearer
    /// token as everything else — it is not a public URL — so Coil's
    /// network layer has to ride the app's authenticated OkHttpClient
    /// rather than build its own.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { ApiClient.authenticatedOkHttpClient() })) }
            .build()
}
