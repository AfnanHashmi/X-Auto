package com.clipcut.di

import com.clipcut.data.db.UnfollowDao
import com.clipcut.domain.unfollows.RealUnfollowsApi
import com.clipcut.domain.unfollows.UnfollowsApi
import com.clipcut.domain.unfollows.UnfollowsService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UnfollowsBindings {
    @Binds abstract fun bindApi(impl: RealUnfollowsApi): UnfollowsApi
}

@Module
@InstallIn(SingletonComponent::class)
object UnfollowsProvides {
    @Provides
    @Singleton
    fun provideService(api: UnfollowsApi, dao: UnfollowDao): UnfollowsService = UnfollowsService(api, dao)
}
