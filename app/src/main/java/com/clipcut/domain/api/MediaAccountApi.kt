package com.clipcut.domain.api

/** A post fetched from the media account, to be auto-embedded into a draft on the main account. */
data class Post(val id: String, val mediaRef: MediaRef, val caption: String)

/**
 * Reads the media account's latest post so it can be embedded into a main-account draft.
 *
 * STUB: implement against the real media-account read API. Swap the binding in
 * `di/ApiModule.kt`; no caller changes.
 */
interface MediaAccountApi {
    suspend fun getLatestPost(): Result<Post>
}
