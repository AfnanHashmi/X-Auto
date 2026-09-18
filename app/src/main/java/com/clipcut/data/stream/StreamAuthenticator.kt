package com.clipcut.data.stream

import com.clipcut.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** A freshly minted stream credential: the query params to sign requests with, and when they die. */
data class SignedPolicy(
    val policy: String,
    val signature: String,
    val expiresAtEpochMs: Long,
)

/**
 * Mints a fresh [SignedPolicy] by running the origin's auth chain end to end:
 *
 * 1. `GET /admin/current-link?key=...`  -> an `/access?token=...` URL (token rotates ~05:00 IST)
 * 2. `GET /access?token=...`            -> sets the `access_session` cookie (~8h)
 * 3. `POST /api/login`                  -> a bearer token, returned in the JSON body (not a cookie)
 * 4. `GET /api/stream/urls`             -> signed per-rendition URLs (~12h), needs BOTH of the above
 *
 * Steps 3 and 4 genuinely both matter: the bearer token alone returns
 * `403 Forbidden: valid access session required`, and the cookie alone returns `401 Not logged in`.
 */
@Singleton
class StreamAuthenticator @Inject constructor(
    @Named("bare") private val bareClient: OkHttpClient,
) {
    suspend fun mint(): SignedPolicy = withContext(Dispatchers.IO) {
        require(BuildConfig.STREAM_ADMIN_KEY.isNotBlank()) { "STREAM_ADMIN_KEY missing from local.properties" }
        require(BuildConfig.STREAM_EMAIL.isNotBlank()) { "STREAM_EMAIL missing from local.properties" }

        // The origin sets access_session partway through a redirect chain, so cookies have to be
        // collected across the whole chain rather than read off the final response.
        val jar = CollectingCookieJar()
        val client = bareClient.newBuilder().cookieJar(jar).build()

        val accessUrl = fetchAccessUrl(client)
        visitAccessLink(client, accessUrl)
        val accessSession = jar.value("access_session")
            ?: throw IOException("No access_session cookie after visiting $accessUrl")
        val bearer = login(client)
        val signedUrl = fetchSignedUrl(client, accessSession, bearer)
        parsePolicy(signedUrl)
    }

    private fun fetchAccessUrl(client: OkHttpClient): String {
        val url = "$ORIGIN/admin/current-link?key=${BuildConfig.STREAM_ADMIN_KEY}"
        return execute(client, Request.Builder().url(url).get().build()).trim()
    }

    /** Visited purely for the `access_session` cookie it sets along the way; the body is ignored. */
    private fun visitAccessLink(client: OkHttpClient, accessUrl: String) {
        client.newCall(Request.Builder().url(accessUrl).get().build()).execute().close()
    }

    /** Collects every cookie the server sets across a redirect chain. */
    private class CollectingCookieJar : okhttp3.CookieJar {
        private val cookies = mutableMapOf<String, String>()

        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            cookies.forEach { this.cookies[it.name] = it.value }
        }

        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            cookies.map { (name, value) ->
                okhttp3.Cookie.Builder().name(name).value(value).domain(url.host).build()
            }

        fun value(name: String): String? = cookies[name]
    }

    private fun login(client: OkHttpClient): String {
        val body = JSONObject()
            .put("email", BuildConfig.STREAM_EMAIL)
            .put("password", BuildConfig.STREAM_PASSWORD)
            .toString()
        val request = Request.Builder().url("$ORIGIN/api/login")
            .header("Content-Type", "application/json")
            .header(APP_PLATFORM_HEADER, APP_PLATFORM_VALUE)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return JSONObject(execute(client, request)).getString("token")
    }

    /** Needs both credentials: the bearer alone 403s, the cookie alone 401s. */
    private fun fetchSignedUrl(client: OkHttpClient, accessSession: String, bearer: String): String {
        val request = Request.Builder().url("$ORIGIN/api/stream/urls")
            .header("Cookie", "access_session=$accessSession")
            .header("Authorization", "Bearer $bearer")
            .header(APP_PLATFORM_HEADER, APP_PLATFORM_VALUE)
            .get()
            .build()
        return JSONObject(execute(client, request))
            .getJSONObject("qualities")
            .getJSONObject(RENDITION)
            .getString("url")
    }

    /** Pulls `policy`/`signature` off the minted URL and decodes the policy's `url_expire`. */
    private fun parsePolicy(signedUrl: String): SignedPolicy {
        val httpUrl = signedUrl.toHttpUrl()
        val policy = httpUrl.queryParameter("policy")
            ?: throw IOException("Minted URL has no policy param: $signedUrl")
        val signature = httpUrl.queryParameter("signature")
            ?: throw IOException("Minted URL has no signature param: $signedUrl")

        val padded = policy.padEnd(policy.length + (4 - policy.length % 4) % 4, '=')
        val decoded = String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE))
        val expiresAt = JSONObject(decoded).getLong("url_expire")

        return SignedPolicy(policy = policy, signature = signature, expiresAtEpochMs = expiresAt)
    }

    private fun execute(client: OkHttpClient, request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IOException("${request.method} ${request.url.encodedPath} failed: HTTP ${response.code}: $body")
        }
        body
    }

    private companion object {
        const val ORIGIN = "https://biggboss24hrs.com"
        const val RENDITION = "q1080"
        const val APP_PLATFORM_HEADER = "x-app-platform"
        const val APP_PLATFORM_VALUE = "Streamflix-App"
    }
}
