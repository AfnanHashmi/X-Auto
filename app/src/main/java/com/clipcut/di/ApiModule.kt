package com.clipcut.di

import com.clipcut.domain.api.CaptionEnhancer
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaUploader
import com.clipcut.domain.api.fake.FakeCaptionEnhancer
import com.clipcut.domain.api.fake.FakeMainAccountApi
import com.clipcut.domain.api.fake.FakeMediaAccountApi
import com.clipcut.domain.api.fake.FakeMediaUploader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the four external-API interfaces to their FAKE implementations, for testing. Reaching the
 * Caption screen auto-uploads (`CaptionViewModel.init` -> `uploadClip()`), so with the real
 * bindings every export fires a real public post. To go live, swap each binding to its `Real*`
 * counterpart in `domain.api.real` (backed by credentials in `local.properties`, see `XApiModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ApiModule {
    @Binds
    abstract fun bindMediaUploader(impl: FakeMediaUploader): MediaUploader

    @Binds
    abstract fun bindMediaAccountApi(impl: FakeMediaAccountApi): MediaAccountApi

    @Binds
    abstract fun bindMainAccountApi(impl: FakeMainAccountApi): MainAccountApi

    @Binds
    abstract fun bindCaptionEnhancer(impl: FakeCaptionEnhancer): CaptionEnhancer
}
