package com.clipcut.di

import com.clipcut.domain.api.CaptionEnhancer
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaUploader
import com.clipcut.domain.api.fake.FakeMainAccountApi
import com.clipcut.domain.api.fake.FakeMediaAccountApi
import com.clipcut.domain.api.fake.FakeMediaUploader
import com.clipcut.domain.api.real.RealCaptionEnhancer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * DRY RUN for X: both X accounts are bound to fakes, so exporting and posting publish nothing.
 * The OpenAI caption enhancer stays real (it only rewrites text in the box, never posts).
 *
 * The media and main accounts are faked TOGETHER on purpose: the main post links to the media
 * post, so faking only the media side would let "Continue to post" publish a real tweet on the
 * main account pointing at a dead placeholder URL. To go live, swap the three X bindings back to
 * `RealMediaUploader` / `RealMediaAccountApi` / `RealMainAccountApi` (in `domain.api.real`).
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
    abstract fun bindCaptionEnhancer(impl: RealCaptionEnhancer): CaptionEnhancer
}
