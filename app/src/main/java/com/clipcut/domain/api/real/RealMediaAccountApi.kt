package com.clipcut.domain.api.real

import com.clipcut.data.x.MediaClients
import com.clipcut.domain.api.MediaAccount
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaRef
import com.clipcut.domain.api.Post
import javax.inject.Inject

class RealMediaAccountApi @Inject constructor(
    private val clients: MediaClients,
) : MediaAccountApi {
    override suspend fun getLatestPost(account: MediaAccount): Result<Post> = runCatching {
        val client = clients.clientFor(account)
        val userId = client.getMe().id
        val latest = client.getRecentTweets(userId, maxResults = 5).firstOrNull()
            ?: error("The ${account.label} account has no tweets yet")
        Post(
            id = latest.id,
            // /video/1 is what makes this unfurl as a playable embedded video card, not a plain
            // link preview -- see RealMediaUploader for where this was confirmed empirically.
            mediaRef = MediaRef(id = latest.id, url = "https://x.com/i/status/${latest.id}/video/1"),
            caption = latest.text,
        )
    }
}
