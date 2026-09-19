package com.clipcut.domain.api.fake

import com.clipcut.domain.api.MediaRef
import com.clipcut.domain.api.MediaUploader
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject

/** Fake used until a real media-account upload SDK is wired in. Always succeeds after a delay. */
class FakeMediaUploader @Inject constructor() : MediaUploader {
    override suspend fun upload(clipFile: File): Result<MediaRef> {
        delay(800)
        return Result.success(MediaRef(id = "fake-${clipFile.name}", url = "https://example.invalid/media/${clipFile.name}"))
    }
}
