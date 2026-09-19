package com.clipcut.domain.api.real

import com.clipcut.data.x.XApiClient
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MainPostText
import com.clipcut.domain.api.MediaRef
import com.clipcut.domain.api.OutcomeUnknownException
import com.clipcut.data.x.XApiException
import javax.inject.Inject
import javax.inject.Named

/**
 * Posts the caption with the media account's post URL appended, which X unfurls into an embedded
 * card showing that post's video and the media account's name.
 *
 * Deliberately does NOT re-upload the clip under this account: that would put a second,
 * independently uploaded copy of the same video on X across two accounts under common control,
 * which reads as duplicate content. One upload (on the media account), referenced here.
 */
class RealMainAccountApi @Inject constructor(
    @Named("main") private val mainAccountClient: XApiClient,
) : MainAccountApi {
    override suspend fun post(caption: String, mediaRef: MediaRef): Result<Unit> = runCatching {
        try {
            mainAccountClient.createTweet(text = MainPostText.build(caption, mediaRef.url))
        } catch (e: XApiException) {
            if ((e.httpCode ?: 0) >= 500) throw OutcomeUnknownException("Main post may have gone out: ${e.message}", e)
            throw e
        } catch (e: java.io.IOException) {
            throw OutcomeUnknownException("Main post may have gone out (no response): ${e.message}", e)
        }
        Unit
    }
}
