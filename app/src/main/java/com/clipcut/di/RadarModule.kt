package com.clipcut.di

import com.clipcut.domain.radar.RadarApi
import com.clipcut.domain.radar.RadarService
import com.clipcut.domain.radar.RealRadarApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RadarBindings {
    @Binds abstract fun bindApi(impl: RealRadarApi): RadarApi
}

@Module
@InstallIn(SingletonComponent::class)
object RadarProvides {
    @Provides
    @Singleton
    fun provideService(api: RadarApi): RadarService = RadarService(api)
}
