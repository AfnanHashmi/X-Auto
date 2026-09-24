package com.clipcut.domain.api

/** A post fetched from the media account, to be auto-embedded into a draft on the main account. */
data class Post(val id: String, val mediaRef: MediaRef, val caption: String)

/** Reads a media account's latest post so it can be embedded into a main-account draft. */
interface MediaAccountApi {
    suspend fun getLatestPost(account: MediaAccount): Result<Post>
}
