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
 * Binds the four external-API interfaces to their fakes.
 *
 * To go live with a real backend, write the real implementation (e.g. `RealMediaUploader`),
 * swap the type on the right of each `@Binds` function, and delete the corresponding fake.
 * No ViewModel or UI code changes.
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
