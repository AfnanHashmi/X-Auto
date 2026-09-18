package com.clipcut.domain.api.fake

import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MediaRef
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject

/** Fake used until the real main-account posting API is wired in. Logs and succeeds. */
class FakeMainAccountApi @Inject constructor() : MainAccountApi {
    override suspend fun post(caption: String, clipFile: File, mediaRef: MediaRef): Result<Unit> {
        delay(500)
        return Result.success(Unit)
    }
}
