package com.clipcut.di

import com.clipcut.domain.api.CaptionEnhancer
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaUploader
import com.clipcut.domain.api.real.RealCaptionEnhancer
import com.clipcut.domain.api.real.RealMainAccountApi
import com.clipcut.domain.api.real.RealMediaAccountApi
import com.clipcut.domain.api.real.RealMediaUploader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * LIVE: bound to the real X and OpenAI APIs, against the credentials in `local.properties`
 * (see [XApiModule]). Exporting a clip publishes real, public posts -- the media account's
 * "BB LOGS - NNN" post fires as soon as the Caption screen opens, and the main account's post
 * fires on "Continue to post".
 *
 * To go back to a no-network dry run, swap each binding to its `Fake*` counterpart in
 * `domain.api.fake`; no caller changes are needed either way.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ApiModule {
    @Binds
    abstract fun bindMediaUploader(impl: RealMediaUploader): MediaUploader

    @Binds
    abstract fun bindMediaAccountApi(impl: RealMediaAccountApi): MediaAccountApi

    @Binds
    abstract fun bindMainAccountApi(impl: RealMainAccountApi): MainAccountApi

    @Binds
    abstract fun bindCaptionEnhancer(impl: RealCaptionEnhancer): CaptionEnhancer
}
