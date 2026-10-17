package com.clipcut.domain.publish

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The website call. Redirects are not followed (a POST must not quietly go elsewhere). The key goes only in the
 * Authorization header; it is never logged, and no exception from here carries it.
 */
class OkHttpWebsiteHttp(base: OkHttpClient, private val url: String = PUBLISH_URL) : WebsiteHttp {
    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun post(json: String, key: String): WebsiteReply = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val detail = runCatching { JSONObject(text).let { it.optString("error").ifBlank { it.optString("message") } } }.getOrDefault("")
            WebsiteReply(response.code, detail)
        }
    }
}

/** The key, encrypted with an Android Keystore AES key that never leaves the secure hardware or the app. */
class KeystoreKeyVault(context: Context) : KeyVault {
    private val prefs = context.getSharedPreferences("publish_secret", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun get(): String? {
        val stored = prefs.getString("k", null) ?: return null
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull() // unreadable (for example the Keystore key was reset): treated as no key
    }

    override fun set(key: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("k", Base64.encodeToString(cipher.iv + enc, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        prefs.edit().remove("k").apply()
    }

    private companion object {
        const val ALIAS = "website_publish_key_v1"
        const val IV_BYTES = 12
    }
}

/**
 * The key from local.properties (WEBSITE_PUBLISH_KEY) when there is one; otherwise one saved earlier with the old Settings
 * screen, which is no longer in the app, so that a key already stored keeps working until it is put in local.properties.
 */
class ConfiguredKeyVault(private val configured: String, private val saved: KeyVault) : KeyVault {
    override fun get(): String? = configured.trim().ifEmpty { null } ?: saved.get()
    override fun set(key: String) = saved.set(key)
    override fun clear() = saved.clear()
}

class PrefsPendingStore(context: Context) : PendingStore {
    private val prefs = context.getSharedPreferences("publish_pending", Context.MODE_PRIVATE)
    override fun load(kind: MeterKind): String? = prefs.getString(kind.wire, null)
    override fun save(kind: MeterKind, json: String?) {
        prefs.edit().apply { if (json == null) remove(kind.wire) else putString(kind.wire, json) }.apply()
    }
}
