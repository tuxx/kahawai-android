package com.kolktech.kahawai.data.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import com.kolktech.kahawai.BuildConfig
import com.kolktech.kahawai.data.auth.ServerConfigStore
import com.kolktech.kahawai.data.auth.TokenStore
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/// Builds the two OkHttp/Retrofit pairs the app needs against the
/// user-configured hub: `plain` (no Authorization header, used for
/// login/refresh/bootstrap) and `authenticated` (attaches + refreshes
/// the bearer token, used for everything else — including artwork,
/// which Coil loads through [authenticatedOkHttpClient]). Base URL is
/// only known at runtime (self-hosted, no fixed domain), so both are
/// rebuilt lazily and invalidated by [reset] when the user changes
/// servers.
object ApiClient {
    private lateinit var tokenStore: TokenStore
    private lateinit var serverConfigStore: ServerConfigStore

    /// Guards every field below. [reset] runs on the main thread when
    /// the user changes servers, while the cache-or-build accessors
    /// (private `plainClient()`/`authClient()`/etc.) are read from
    /// arbitrary threads — OkHttp's dispatcher (interceptors, and
    /// [TokenAuthenticator]'s refresh callback, which calls
    /// [plainApiService]) as well as whatever coroutine dispatcher a
    /// repository call happens to run on. Without a shared lock, reset()
    /// nulling these fields could interleave with another thread's
    /// read-null-then-build sequence: the reader either caches a second,
    /// divergent client/retrofit instance or — worse — finishes building
    /// against the base URL that was just invalidated. synchronized is
    /// reentrant per-thread, so accessors that call each other (e.g.
    /// authRetrofit() -> authClient()) nest safely, and every builder
    /// call is cheap (no I/O under the lock), so contention is a non-issue.
    private val lock = Any()
    private var plainClient: OkHttpClient? = null
    private var authClient: OkHttpClient? = null
    private var plainRetrofit: Retrofit? = null
    private var authRetrofit: Retrofit? = null

    fun init(tokenStore: TokenStore, serverConfigStore: ServerConfigStore) {
        this.tokenStore = tokenStore
        this.serverConfigStore = serverConfigStore
    }

    fun reset() = synchronized(lock) {
        plainClient = null
        authClient = null
        plainRetrofit = null
        authRetrofit = null
    }

    fun plainApiService(): ApiService = plainRetrofit().create(ApiService::class.java)

    /// One-off service against a CANDIDATE hub URL during setup — lets the
    /// reachability check run before the URL is committed to
    /// [ServerConfigStore], so a typo'd address never becomes the stored
    /// base URL. Not cached: setup is a one-shot flow.
    fun probeApiService(candidateBaseUrl: String): ApiService =
        buildRetrofit(plainClient(), normalize(candidateBaseUrl)).create(ApiService::class.java)

    /// Follows any redirect on the bootstrap route (typically a proxy's
    /// http→https 301/308) and returns the base URL the hub actually
    /// answers on. Persisting the pre-redirect URL would poison every
    /// later authenticated call: OkHttp strips the Authorization header
    /// whenever a redirect crosses scheme/host/port, so the hub would see
    /// tokenless requests and 401 them even though login itself (which
    /// needs no token) appeared to work.
    suspend fun resolveBaseUrl(candidateBaseUrl: String): String {
        val base = normalize(candidateBaseUrl)
        val probePath = "api/v1/bootstrap"
        val finalUrl = withContext(Dispatchers.IO) {
            plainClient().newCall(Request.Builder().url(base + probePath).build())
                .execute()
                .use { it.request.url.toString() }
        }
        // A proxy that rewrites the path beyond scheme/host isn't a plain
        // canonicalizing redirect — don't guess, keep what the user typed.
        return if (finalUrl.endsWith("/$probePath")) {
            normalize(finalUrl.removeSuffix(probePath))
        } else {
            base
        }
    }

    fun apiService(): ApiService = authRetrofit().create(ApiService::class.java)

    fun authenticatedOkHttpClient(): OkHttpClient = authClient()

    /// Same auth/refresh pipeline as [authenticatedOkHttpClient] (built via
    /// `newBuilder()`, so it's cheap and stays in sync), but for ExoPlayer's
    /// OkHttpDataSource rather than Retrofit: reading an HLS segment can
    /// stall well past a normal API call's 10s if the hub's transcode
    /// pipeline is still producing it, and OkHttp's default read/write
    /// timeout (10s, same as the connect timeout set below) would surface
    /// that stall to ExoPlayer as a generic source error indistinguishable
    /// from an actual failure.
    fun streamingOkHttpClient(): OkHttpClient = authClient().newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun baseUrl(): String =
        baseUrlOrNull() ?: error("hub server not configured yet")

    /// Non-throwing counterpart of [baseUrl] for callers that can run
    /// during composition (e.g. artwork URL construction) and may be
    /// asked for a URL in the brief window between "change server"
    /// clearing [serverConfigStore] and navigation actually tearing down
    /// the screens that reference it.
    fun baseUrlOrNull(): String? = serverConfigStore.baseUrl?.let(::normalize)

    private fun normalize(url: String): String = if (url.endsWith("/")) url else "$url/"

    private fun loggingInterceptor() = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private fun plainClient(): OkHttpClient = synchronized(lock) {
        plainClient ?: OkHttpClient.Builder()
            .apply { if (BuildConfig.DEBUG) addInterceptor(loggingInterceptor()) }
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
            .also { plainClient = it }
    }

    private fun authClient(): OkHttpClient = synchronized(lock) {
        authClient ?: OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .apply { if (BuildConfig.DEBUG) addInterceptor(loggingInterceptor()) }
            .authenticator(TokenAuthenticator(tokenStore) { plainApiService() })
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
            .also { authClient = it }
    }

    private fun plainRetrofit(): Retrofit = synchronized(lock) {
        plainRetrofit ?: buildRetrofit(plainClient(), baseUrl()).also { plainRetrofit = it }
    }

    private fun authRetrofit(): Retrofit = synchronized(lock) {
        authRetrofit ?: buildRetrofit(authClient(), baseUrl()).also { authRetrofit = it }
    }

    private fun buildRetrofit(client: OkHttpClient, baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(apiJson.asConverterFactory("application/json".toMediaType()))
            .build()
}
