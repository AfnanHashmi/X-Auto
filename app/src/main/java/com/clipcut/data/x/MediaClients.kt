package com.clipcut.data.x

import com.clipcut.domain.api.MediaAccount
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One [XApiClient] per media account, built lazily from that account's own credentials. Callers ask
 * for a client for a specific account and keep it for the whole operation, rather than asking "the
 * media client" each time, which is what makes a mid-operation account switch harmless.
 */
@Singleton
class MediaClients @Inject constructor(private val okHttpClient: OkHttpClient) {
    private val clients = ConcurrentHashMap<MediaAccount, XApiClient>()

    fun clientFor(account: MediaAccount): XApiClient {
        check(account.isConfigured) { "The ${account.label} account's keys are missing from local.properties" }
        return clients.getOrPut(account) {
            val c = account.credentials
            XApiClient(okHttpClient, OAuth1aSigner(c.consumerKey, c.consumerSecret, c.accessToken, c.accessSecret))
        }
    }
}
