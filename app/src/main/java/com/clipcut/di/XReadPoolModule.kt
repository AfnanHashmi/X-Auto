package com.clipcut.di

import com.clipcut.domain.api.RealXAppGateway
import com.clipcut.domain.api.XReadPool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object XReadPoolModule {
    /** One pool for the whole app, so every screen sees the same balances and the same call counts. */
    @Provides
    @Singleton
    fun providePool(client: OkHttpClient): XReadPool = XReadPool(RealXAppGateway(client))
}
