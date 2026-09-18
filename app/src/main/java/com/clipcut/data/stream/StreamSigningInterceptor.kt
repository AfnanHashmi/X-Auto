package com.clipcut.data.stream

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stamps the current [SignedPolicy] onto every request for stream media.
 *
 * This is what lets the credential rotate without disturbing playback. The origin's signature
 * validates against the policy's expiry rather than the request path (verified: one q1080-minted
 * pair fetches the master playlist, the variant chunklist, and `.m4s` segments alike), so swapping
 * the two query params per-request is sufficient. ExoPlayer holds one unchanging URI and never has
 * to be re-prepared -- no rebuffer, no lost DVR position -- and `SegmentFetcher`'s export
 * downloads get the same treatment for free, since both run through this OkHttp client.
 *
 * A 401 means the credential died sooner than its stated expiry, so force a re-mint and retry once.
 */
@Singleton
class StreamSigningInterceptor @Inject constructor(
    private val policyProvider: SignedPolicyProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.startsWith(STREAM_PATH_PREFIX)) return chain.proceed(request)

        val policy = runBlocking { policyProvider.current() } ?: return chain.proceed(request)
        val response = chain.proceed(request.signedWith(policy))
        if (response.code != 401) return response

        val refreshed = runBlocking { policyProvider.invalidateAndRefresh() } ?: return response
        response.close()
        return chain.proceed(request.signedWith(refreshed))
    }

    private fun okhttp3.Request.signedWith(policy: SignedPolicy) = newBuilder()
        .url(
            url.newBuilder()
                .removeAllQueryParameters("policy")
                .removeAllQueryParameters("signature")
                .addQueryParameter("policy", policy.policy)
                .addQueryParameter("signature", policy.signature)
                .build(),
        )
        .build()

    private companion object {
        const val STREAM_PATH_PREFIX = "/app/stream/"
    }
}
