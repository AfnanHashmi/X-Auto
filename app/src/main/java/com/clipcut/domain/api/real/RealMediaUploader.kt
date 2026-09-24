package com.clipcut.domain.api.real

import com.clipcut.data.x.BbLogsCounter
import com.clipcut.data.x.MediaClients
import com.clipcut.data.x.XApiException
import com.clipcut.domain.api.MediaAccount
import com.clipcut.domain.api.MediaRef
import com.clipcut.domain.api.MediaUploader
import com.clipcut.domain.api.OutcomeUnknownException
import java.io.File
import javax.inject.Inject

/**
 * Uploads the clip to X under the chosen media account and posts it as that account's own tweet,
 * with the shared "BB LOGS - <counter>" caption (see [BbLogsCounter], which numbers each account
 * separately). This is the *only* upload of the video -- the main account's post links to the resulting
 * post rather than uploading its own copy. The returned [MediaRef.url] is that post's permalink.
 */
class RealMediaUploader @Inject constructor(
    private val clients: MediaClients,
    private val bbLogsCounter: BbLogsCounter,
) : MediaUploader {
    override suspend fun upload(clipFile: File, account: MediaAccount): Result<MediaRef> = runCatching {
        val client = clients.clientFor(account)
        val handle = client.getMe().username
        // The keys must belong to the account that was asked for. Pasting one account's keys under the
        // other's name would otherwise publish the clip from the wrong account, and that is public.
        check(handle.equals(account.handle, ignoreCase = true)) {
            "The ${account.label} keys belong to @$handle, not @${account.handle}. Nothing was uploaded."
        }
        val mediaId = client.uploadVideo(clipFile)
        val tweet = try {
            client.createTweet(text = bbLogsCounter.nextCaption(account), mediaIds = listOf(mediaId))
        } catch (e: XApiException) {
            // A 4xx is a definitive rejection (nothing was posted); a 5xx may have posted anyway.
            if ((e.httpCode ?: 0) >= 500) throw OutcomeUnknownException("Media post may have gone out: ${e.message}", e)
            throw e
        } catch (e: java.io.IOException) {
            throw OutcomeUnknownException("Media post may have gone out (no response): ${e.message}", e)
        }
        bbLogsCounter.commit(account) // only advance the counter once the post actually succeeded.
        // The /video/1 suffix is what makes X unfurl this into an embedded, playable video card;
        // the bare /<handle>/status/<id> permalink instead unfurled as a plain quote-style link
        // preview (confirmed empirically -- the bare form was the bug).
        MediaRef(id = tweet.id, url = "https://x.com/$handle/status/${tweet.id}/video/1")
    }
}
