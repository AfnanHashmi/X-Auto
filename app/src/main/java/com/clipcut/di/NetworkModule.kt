package com.clipcut.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
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
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient()
}
