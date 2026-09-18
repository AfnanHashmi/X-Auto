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
 * Binds the four external-API interfaces to their real implementations, backed by credentials in
 * `local.properties` (see `XApiModule` / `BuildConfig`). Swap to a `Fake*` here (see git history)
 * if you need to iterate on export/playback logic without hitting real X/OpenAI APIs -- reaching
 * the Caption screen auto-uploads (`CaptionViewModel.init` -> `uploadClip()`), so every export
 * test with the real bindings fires a real public post.
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
