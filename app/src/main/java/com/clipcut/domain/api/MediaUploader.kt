package com.clipcut.domain.api

import java.io.File

/**
 * Reference returned by [MediaUploader.upload], threaded into [MainAccountApi.post].
 * [authorHandle] (blank if unknown) is the media account's own @handle, used to credit it in the
 * main account's post when the clip is re-uploaded there natively.
 */
data class MediaRef(val id: String, val url: String, val authorHandle: String = "")

/**
 * Uploads an exported clip to the "media" account.
 *
 * STUB: implement against the real media-account upload API/SDK. Swap the binding in
 * `di/ApiModule.kt` from `FakeMediaUploader` to the real implementation; no caller changes.
 */
interface MediaUploader {
    suspend fun upload(clipFile: File): Result<MediaRef>
}
