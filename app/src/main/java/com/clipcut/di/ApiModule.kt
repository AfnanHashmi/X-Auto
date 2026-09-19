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
 * Media account: LIVE -- the real X API uploads the clip and publishes "BB LOGS - NNN" as soon as the
 * Caption screen opens. Main account: NOT posted from the app -- [ClipboardMainAccountApi] copies the
 * caption plus the embed link to the clipboard for the user to post themselves. The AI caption
 * enhancer is real (it only rewrites text in the box).
 *
 * To post the main account from the app again, bind [MainAccountApi] to `RealMainAccountApi`.
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
