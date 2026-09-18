package com.clipcut.domain.api

import java.io.File

/**
 * Publishes the final caption + media to the main account, natively re-uploading [clipFile]
 * under the main account's own credentials (crediting [MediaRef.authorHandle] in the caption)
 * rather than quote-tweeting or linking the media account's post.
 */
interface MainAccountApi {
    suspend fun post(caption: String, clipFile: File, mediaRef: MediaRef): Result<Unit>
}
