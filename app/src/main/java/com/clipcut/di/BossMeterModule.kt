package com.clipcut.di

import com.clipcut.domain.bossmeter.BossMeterApi
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
}
