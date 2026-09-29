package com.clipcut.domain.api

import java.io.File

/**
 * Reference returned by [MediaUploader.upload], threaded into [MainAccountApi.post]. [url] is the
 * media account's public post permalink -- what the main account's post links to.
 */
data class MediaRef(val id: String, val url: String)

/**
 * Uploads an exported clip to the "media" account.
 *
 * STUB: implement against the real media-account upload API/SDK. Swap the binding in
 * `di/ApiModule.kt` from `FakeMediaUploader` to the real implementation; no caller changes.
 */
interface MediaUploader {
    suspend fun upload(clipFile: File): Result<MediaRef>
}

/**
 * A publish request whose outcome is UNKNOWN: it was sent, but no definitive answer came back
 * (timeout, dropped connection, HTTP 5xx), so the post may or may not exist on X. Callers must NOT
 * retry this automatically -- doing so is how one clip becomes two identical public posts.
 */
class OutcomeUnknownException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
