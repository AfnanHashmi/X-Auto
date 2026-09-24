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
 * The main account's [XApiClient]. The media accounts (DK, BB) are not provided here: which one is
 * in use is decided per operation, so they come from `MediaClients` instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object XApiModule {
    @Provides
    @Singleton
    @Named("main")
    fun provideMainXApiClient(okHttpClient: OkHttpClient): XApiClient = XApiClient(
        okHttpClient,
        OAuth1aSigner(
            consumerKey = BuildConfig.X_MAIN_CONSUMER_KEY,
            consumerSecret = BuildConfig.X_MAIN_CONSUMER_SECRET,
            accessToken = BuildConfig.X_MAIN_ACCESS_TOKEN,
            accessTokenSecret = BuildConfig.X_MAIN_ACCESS_SECRET,
        ),
    )
}
