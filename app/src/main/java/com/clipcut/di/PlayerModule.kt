package com.clipcut.di

import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {
    // Required by the origin alongside the signed policy/signature query params.
    private const val APP_PLATFORM_HEADER = "x-app-platform"
    private const val APP_PLATFORM_VALUE = "Streamflix-App"

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header(APP_PLATFORM_HEADER, APP_PLATFORM_VALUE)
                    .build()
                chain.proceed(request)
            }
            .build()

    @Provides
    @Singleton
    fun provideHttpDataSourceFactory(okHttpClient: OkHttpClient): HttpDataSource.Factory =
        OkHttpDataSource.Factory(okHttpClient)

    @Provides
    @Singleton
    fun provideMediaSourceFactory(dataSourceFactory: HttpDataSource.Factory): MediaSource.Factory =
        HlsMediaSource.Factory(dataSourceFactory)
}
