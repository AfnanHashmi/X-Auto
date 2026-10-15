package com.clipcut.di

import com.clipcut.domain.taglines.RealTaglinesApi
import com.clipcut.domain.taglines.TaglinesApi
import com.clipcut.domain.taglines.TaglinesService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TaglinesBindings {
    @Binds abstract fun bindApi(impl: RealTaglinesApi): TaglinesApi
}

@Module
@InstallIn(SingletonComponent::class)
object TaglinesProvides {
    @Provides
    @Singleton
    fun provideService(api: TaglinesApi): TaglinesService = TaglinesService(api)
}
