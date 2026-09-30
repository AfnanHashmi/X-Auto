package com.clipcut.domain.api.fake

import com.clipcut.domain.api.MediaAccount
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaRef
import com.clipcut.domain.api.Post
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Fake used until the real media-account read API is wired in. */
class FakeMediaAccountApi @Inject constructor() : MediaAccountApi {
    override suspend fun getLatestPost(account: MediaAccount): Result<Post> {
        delay(300)
        return Result.success(
            Post(
                id = "fake-post-1",
                mediaRef = MediaRef(id = "fake-media-1", url = "https://example.invalid/media/latest.mp4"),
                caption = "Placeholder caption from the media account's latest post.",
            ),
        )
    }
}
