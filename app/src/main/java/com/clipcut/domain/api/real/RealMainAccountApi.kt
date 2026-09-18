package com.clipcut.domain.api.real

import com.clipcut.data.x.XApiClient
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MediaRef
import javax.inject.Inject
import javax.inject.Named

/** Posts the caption as a quote-tweet of the media account's post (see [RealMediaUploader] for why). */
class RealMainAccountApi @Inject constructor(
    @Named("main") private val mainAccountClient: XApiClient,
) : MainAccountApi {
    override suspend fun post(caption: String, mediaRef: MediaRef): Result<Unit> = runCatching {
        mainAccountClient.createTweet(text = caption, quoteTweetId = mediaRef.id)
        Unit
    }
}
