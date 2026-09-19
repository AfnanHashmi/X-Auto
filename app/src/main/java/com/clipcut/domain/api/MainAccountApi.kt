package com.clipcut.domain.api

/**
 * Publishes the final caption to the main account, referencing the media account's post
 * ([MediaRef.url]) rather than re-uploading the clip.
 */
interface MainAccountApi {
    suspend fun post(caption: String, mediaRef: MediaRef): Result<Unit>
}
