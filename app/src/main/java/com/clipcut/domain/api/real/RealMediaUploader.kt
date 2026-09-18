package com.clipcut.domain.api.real

import com.clipcut.data.x.BbLogsCounter
import com.clipcut.data.x.XApiClient
import com.clipcut.domain.api.MediaRef
import com.clipcut.domain.api.MediaUploader
import java.io.File
import javax.inject.Inject
import javax.inject.Named

/**
 * Uploads the clip to X under the media account and posts it as that account's own tweet, with
 * the shared "BB LOGS - <counter>" caption (see [BbLogsCounter]). The returned [MediaRef.id] is
 * that tweet's id (not the raw media id) -- [RealMainAccountApi] needs a tweet id to quote-tweet,
 * since X ties an uploaded media_id to whichever account uploaded it and it can't be attached to
 * a tweet from a *different* account.
 */
class RealMediaUploader @Inject constructor(
    @Named("media") private val mediaAccountClient: XApiClient,
    private val bbLogsCounter: BbLogsCounter,
) : MediaUploader {
    override suspend fun upload(clipFile: File): Result<MediaRef> = runCatching {
        val mediaId = mediaAccountClient.uploadVideo(clipFile)
        val tweet = mediaAccountClient.createTweet(text = bbLogsCounter.nextCaption(), mediaIds = listOf(mediaId))
        val handle = mediaAccountClient.getMe().username
        MediaRef(id = tweet.id, url = "https://x.com/i/status/${tweet.id}", authorHandle = handle)
    }
}
