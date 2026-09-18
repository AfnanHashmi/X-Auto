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
 * the shared "BB LOGS - <counter>" caption (see [BbLogsCounter]). This is the *only* upload of
 * the video -- [RealMainAccountApi] links to the resulting post rather than uploading its own
 * copy. The returned [MediaRef.url] is that post's permalink.
 */
class RealMediaUploader @Inject constructor(
    @Named("media") private val mediaAccountClient: XApiClient,
    private val bbLogsCounter: BbLogsCounter,
) : MediaUploader {
    override suspend fun upload(clipFile: File): Result<MediaRef> = runCatching {
        val handle = mediaAccountClient.getMe().username
        val mediaId = mediaAccountClient.uploadVideo(clipFile)
        val tweet = mediaAccountClient.createTweet(text = bbLogsCounter.nextCaption(), mediaIds = listOf(mediaId))
        bbLogsCounter.commit() // only advance the counter once the post actually succeeded.
        // The /video/1 suffix is what makes X unfurl this into an embedded, playable video card;
        // the bare /<handle>/status/<id> permalink instead unfurled as a plain quote-style link
        // preview (confirmed empirically -- the bare form was the bug).
        MediaRef(id = tweet.id, url = "https://x.com/$handle/status/${tweet.id}/video/1")
    }
}
