package com.clipcut.di

import com.clipcut.domain.api.CaptionEnhancer
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaUploader
import com.clipcut.domain.api.clipboard.ClipboardMainAccountApi
import com.clipcut.domain.api.fake.FakeMediaAccountApi
import com.clipcut.domain.api.fake.FakeMediaUploader
import com.clipcut.domain.api.real.RealCaptionEnhancer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * TEMPORARY DRY RUN for the media account: the media upload and the "latest post" lookup are FAKES,
 * so an export publishes nothing to X. The main account only ever copies to the clipboard
 * ([ClipboardMainAccountApi]); the copied link is a placeholder `example.invalid` URL. The AI caption
 * enhancer is real (it only rewrites text in the box).
 *
 * TO GO LIVE AGAIN: bind MediaUploader to `RealMediaUploader` and MediaAccountApi to
 * `RealMediaAccountApi` (in `domain.api.real`), then delete any clip records whose mediaRefId starts
 * with "fake-" so a test clip can never be reused as if it had really been uploaded.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ApiModule {
    @Binds
    abstract fun bindMediaUploader(impl: FakeMediaUploader): MediaUploader

    @Binds
    abstract fun bindMediaAccountApi(impl: FakeMediaAccountApi): MediaAccountApi

    @Binds
    abstract fun bindMainAccountApi(impl: ClipboardMainAccountApi): MainAccountApi

    @Binds
    abstract fun bindCaptionEnhancer(impl: RealCaptionEnhancer): CaptionEnhancer
}
