package com.clipcut.di

import com.clipcut.BuildConfig
import com.clipcut.data.x.OAuth1aSigner
import com.clipcut.data.x.XApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

/**
 * One [XApiClient] per X account -- media and main share the developer app's consumer key/secret
 * but each has its own access token/secret (see `RealMediaUploader`'s doc comment for why the two
 * accounts can't share a single client).
 */
@Module
@InstallIn(SingletonComponent::class)
object XApiModule {
    @Provides
    @Singleton
    @Named("media")
    fun provideMediaXApiClient(okHttpClient: OkHttpClient): XApiClient = XApiClient(
        okHttpClient,
        OAuth1aSigner(
            consumerKey = BuildConfig.X_CONSUMER_KEY,
            consumerSecret = BuildConfig.X_CONSUMER_SECRET,
            accessToken = BuildConfig.X_MEDIA_ACCESS_TOKEN,
            accessTokenSecret = BuildConfig.X_MEDIA_ACCESS_SECRET,
        ),
    )

    @Provides
    @Singleton
    @Named("main")
    fun provideMainXApiClient(okHttpClient: OkHttpClient): XApiClient = XApiClient(
        okHttpClient,
        OAuth1aSigner(
            consumerKey = BuildConfig.X_CONSUMER_KEY,
            consumerSecret = BuildConfig.X_CONSUMER_SECRET,
            accessToken = BuildConfig.X_MAIN_ACCESS_TOKEN,
            accessTokenSecret = BuildConfig.X_MAIN_ACCESS_SECRET,
        ),
    )
}
