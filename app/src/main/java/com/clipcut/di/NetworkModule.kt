package com.clipcut.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /**
     * Plain client for the X and OpenAI calls. Deliberately no interceptors: the old app-wide client
     * added a stream-host platform header and a stream-URL signing interceptor, both for the deleted
     * LLHLS path, and neither belongs on requests to third-party APIs.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // OkHttp's 10s default is too short on this WiFi, where the first byte of a reply has been
        // measured 5-19s out. A DELETE that X had already carried out timed out at 10s and reported
        // itself as "unconfirmed", which is the worst possible answer for an irreversible call.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}
