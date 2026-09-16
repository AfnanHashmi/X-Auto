package com.clipcut.domain.api

/**
 * Publishes the final caption + embedded media to the main account.
 *
 * STUB: implement against the real main-account posting API. Swap the binding in
 * `di/ApiModule.kt`; no caller changes.
 */
interface MainAccountApi {
    suspend fun post(caption: String, mediaRef: MediaRef): Result<Unit>
}
