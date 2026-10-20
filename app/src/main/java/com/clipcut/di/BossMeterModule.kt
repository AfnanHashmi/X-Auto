package com.clipcut.di

import com.clipcut.BuildConfig
import com.clipcut.domain.bossmeter.BossMeterApi
import com.clipcut.domain.bossmeter.IgBossApi
import com.clipcut.domain.bossmeter.RealIgBossApi
import okhttp3.OkHttpClient
import com.clipcut.domain.bossmeter.BossMeterService
import com.clipcut.domain.bossmeter.RealBossMeterApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BossMeterBindings {
    @Binds abstract fun bindApi(impl: RealBossMeterApi): BossMeterApi
}

@Module
@InstallIn(SingletonComponent::class)
object BossMeterProvides {
    @Provides
    @Singleton
    fun provideService(api: BossMeterApi): BossMeterService = BossMeterService(api)

    @Provides
    @Singleton
    fun provideIgApi(client: OkHttpClient): IgBossApi = RealIgBossApi(client, BuildConfig.IG_BASE_URL, BuildConfig.IG_API_KEY)
}
