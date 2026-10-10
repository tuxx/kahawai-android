package com.kolktech.kahawai.data.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import com.kolktech.kahawai.data.network.dto.TokenPair

private val Context.tokenDataStore by preferencesDataStore("kahawai_tokens")

/// Access + refresh tokens, AES-256-GCM encrypted via Tink with the key
/// wrapped by the Android Keystore, persisted in DataStore.
/// `androidx.security:security-crypto`'s EncryptedSharedPreferences/
/// MasterKey were deprecated in 1.1.0-alpha07 (OEM keyset-corruption
/// crashes); DataStore + Tink directly is the replacement Google now
/// documents. Refresh tokens rotate server-side on every use
/// (crates/kahawai-hub/src/auth.rs:298-323), so this store always holds
/// the latest pair, never a history.
///
/// OkHttp's Interceptor/Authenticator run synchronously on a background
/// dispatcher thread (never the main thread), so [accessToken] and
/// [refreshToken] read an in-memory cache rather than suspend — it's
/// hydrated once at construction and kept current by [save]/[clear].
class TokenStore(private val context: Context) : TokenStorage {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withSharedPref(
                context,
                "${context.packageName}.token_keyset",
                "${context.packageName}.token_keyset_prefs",
            )
            .withMasterKeyUri("android-keystore://${context.packageName}.token_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val hydrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cache: Pair<String, String>? = null

    /// Disk read + Keystore decrypt can take a while on slow hardware —
    /// kicked off here instead of blocking [Application.onCreate] with
    /// `runBlocking`, which used to stall the very first frame. Callers on
    /// the main thread that need [hasTokens]/[accessToken] to reflect a
    /// real disk state (e.g. picking the nav graph's start destination)
    /// must await this first; [AuthInterceptor]/[TokenAuthenticator] read
    /// the synchronous properties directly since they run on an OkHttp
    /// background thread after the app is already up, by which point this
    /// has long since completed.
    val hydrated: Deferred<Unit> = hydrationScope.async {
        cache = readFromDisk()
    }

    override val accessToken: String? get() = cache?.first
    override val refreshToken: String? get() = cache?.second
    val hasTokens: Boolean get() = cache != null

    override suspend fun save(tokens: TokenPair) {
        cache = tokens.accessToken to tokens.refreshToken
        context.tokenDataStore.edit { prefs ->
            prefs[KEY_ACCESS] = encrypt(tokens.accessToken)
            prefs[KEY_REFRESH] = encrypt(tokens.refreshToken)
        }
    }

    override suspend fun clear() {
        cache = null
        context.tokenDataStore.edit { it.clear() }
    }

    private suspend fun readFromDisk(): Pair<String, String>? {
        val prefs = context.tokenDataStore.data.first()
        val access = prefs[KEY_ACCESS]?.let(::decrypt) ?: return null
        val refresh = prefs[KEY_REFRESH]?.let(::decrypt) ?: return null
        return access to refresh
    }

    private fun encrypt(value: String): String =
        Base64.encodeToString(aead.encrypt(value.toByteArray(), null), Base64.NO_WRAP)

    private fun decrypt(value: String): String =
        String(aead.decrypt(Base64.decode(value, Base64.NO_WRAP), null))

    private companion object {
        val KEY_ACCESS = stringPreferencesKey("access_token")
        val KEY_REFRESH = stringPreferencesKey("refresh_token")
    }
}
