package com.clipcut.domain.api.real

import com.clipcut.data.x.XApiClient
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaRef
import com.clipcut.domain.api.Post
import javax.inject.Inject
import javax.inject.Named

class RealMediaAccountApi @Inject constructor(
    @Named("media") private val mediaAccountClient: XApiClient,
) : MediaAccountApi {
    override suspend fun getLatestPost(): Result<Post> = runCatching {
        val userId = mediaAccountClient.getMyUserId()
        val latest = mediaAccountClient.getRecentTweets(userId, maxResults = 5).firstOrNull()
            ?: error("Media account has no tweets yet")
        Post(
            id = latest.id,
            mediaRef = MediaRef(id = latest.id, url = "https://x.com/i/status/${latest.id}"),
            caption = latest.text,
        )
    }
}
