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
        // The /<handle>/status/<id> permalink form is what X unfurls into a card showing the
        // video and the media account's name; the /i/status/<id> form redirects but is opaque.
        MediaRef(id = tweet.id, url = "https://x.com/$handle/status/${tweet.id}")
    }
}
