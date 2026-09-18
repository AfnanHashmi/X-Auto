package com.clipcut.di

import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import com.clipcut.data.stream.StreamSigningInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {
    // Required by the origin alongside the signed policy/signature query params.
    private const val APP_PLATFORM_HEADER = "x-app-platform"
    private const val APP_PLATFORM_VALUE = "Streamflix-App"

    private fun appPlatformInterceptor(): okhttp3.Interceptor = okhttp3.Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header(APP_PLATFORM_HEADER, APP_PLATFORM_VALUE).build())
    }

    /**
     * Unsigned client for the auth chain itself. Kept separate from the main client to break what
     * would otherwise be a dependency cycle: signing interceptor -> policy provider ->
     * authenticator -> http client.
     */
    @Provides
    @Singleton
    @Named("bare")
    fun provideBareOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(appPlatformInterceptor())
            .build()

    /**
     * The app-wide client. Everything stream-related (ExoPlayer via [OkHttpDataSource],
     * `SegmentFetcher`'s export downloads) runs through here and so picks up the current signed
     * policy automatically; [StreamSigningInterceptor] no-ops on non-stream hosts like X/OpenAI.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(signingInterceptor: StreamSigningInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(appPlatformInterceptor())
            .addInterceptor(signingInterceptor)
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
