package com.clipcut.data.x

import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs requests to X's API per OAuth 1.0a (RFC 5849), still required for the v1.1 media-upload
 * endpoints regardless of which auth style is used for v2 endpoints. One signer instance is
 * bound to one X account's credentials (consumer key/secret are shared across accounts under one
 * developer app; access token/secret are per-account).
 */
class OAuth1aSigner(
    private val consumerKey: String,
    private val consumerSecret: String,
    private val accessToken: String,
    private val accessTokenSecret: String,
) {
    /**
     * Builds the `Authorization` header value for a request to [url] (no query string) with
     * [method] (e.g. "POST") and [signedParams] -- the union of any query params and any
     * `application/x-www-form-urlencoded` body params. Do NOT include multipart file parts here;
     * per the OAuth 1.0a spec, binary body parts are excluded from the signature base string.
     */
    fun authorizationHeader(method: String, url: String, signedParams: Map<String, String> = emptyMap()): String {
        val oauthParams = mutableMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to nonce(),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_token" to accessToken,
            "oauth_version" to "1.0",
        )

        val allParams = (oauthParams + signedParams).toSortedMap()
        val baseString = buildString {
            append(method.uppercase())
            append('&')
            append(encode(url))
            append('&')
            append(encode(allParams.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }))
        }
        val signingKey = "${encode(consumerSecret)}&${encode(accessTokenSecret)}"
        oauthParams["oauth_signature"] = hmacSha1(baseString, signingKey)

        return "OAuth " + oauthParams.entries.joinToString(", ") { (k, v) -> "${encode(k)}=\"${encode(v)}\"" }
    }

    private fun nonce(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun hmacSha1(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return android.util.Base64.encodeToString(
            mac.doFinal(data.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP,
        )
    }

    /** RFC 3986 percent-encoding, stricter than [URLEncoder] (which is form-encoding, not URI-encoding). */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
}
