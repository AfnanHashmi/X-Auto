package com.clipcut.di

import com.clipcut.domain.api.CaptionEnhancer
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaUploader
import com.clipcut.domain.api.clipboard.ClipboardMainAccountApi
import com.clipcut.domain.api.real.RealCaptionEnhancer
import com.clipcut.domain.api.real.RealMediaAccountApi
import com.clipcut.domain.api.real.RealMediaUploader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * LIVE: the media upload and the "latest post" lookup hit the real X API (media account). The main
 * account only ever copies to the clipboard ([ClipboardMainAccountApi]), so nothing is posted to it
 * from the app. The AI caption enhancer is real too.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ApiModule {
    @Binds
    abstract fun bindMediaUploader(impl: RealMediaUploader): MediaUploader

    @Binds
    abstract fun bindMediaAccountApi(impl: RealMediaAccountApi): MediaAccountApi

    @Binds
    abstract fun bindMainAccountApi(impl: ClipboardMainAccountApi): MainAccountApi

    @Binds
    abstract fun bindCaptionEnhancer(impl: RealCaptionEnhancer): CaptionEnhancer
}
