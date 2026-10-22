package com.clipcut.di

import com.clipcut.BuildConfig
import com.clipcut.domain.scheduler.RealSchedulerApi
import com.clipcut.domain.scheduler.SchedulerApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SchedulerModule {
    @Provides
    @Singleton
    fun provideSchedulerApi(client: OkHttpClient): SchedulerApi = RealSchedulerApi(client, BuildConfig.SCHEDULER_URL, BuildConfig.SCHEDULER_TOKEN)
}
