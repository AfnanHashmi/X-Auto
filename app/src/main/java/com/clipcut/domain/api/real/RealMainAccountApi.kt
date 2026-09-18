package com.clipcut.domain.api.real

import com.clipcut.data.x.XApiClient
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MediaRef
import java.io.File
import javax.inject.Inject
import javax.inject.Named

/**
 * Re-uploads [clipFile] as native video under the main account's own credentials (X ties an
 * uploaded media_id to whichever account uploaded it, so the media account's upload can't be
 * attached directly), crediting the media account by @handle in the caption text -- not a quote
 * tweet or a link, just the same video natively posted with attribution.
 */
class RealMainAccountApi @Inject constructor(
    @Named("main") private val mainAccountClient: XApiClient,
) : MainAccountApi {
    override suspend fun post(caption: String, clipFile: File, mediaRef: MediaRef): Result<Unit> = runCatching {
        val mediaId = mainAccountClient.uploadVideo(clipFile)
        val text = if (mediaRef.authorHandle.isNotBlank()) "$caption (via @${mediaRef.authorHandle})" else caption
        mainAccountClient.createTweet(text = text, mediaIds = listOf(mediaId))
        Unit
    }
}
