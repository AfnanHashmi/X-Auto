package com.clipcut.di

import android.content.Context
import com.clipcut.domain.llm.LlmClient
import com.clipcut.domain.taglines.DeepLookService
import com.clipcut.domain.taglines.DeepLookStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.clipcut.domain.taglines.RealTaglinesApi
import com.clipcut.domain.taglines.TaglineAnalyst
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

    @Provides
    @Singleton
    fun provideAnalyst(llm: LlmClient): TaglineAnalyst = TaglineAnalyst(llm)

    @Provides
    @Singleton
    fun provideDeepLookStore(@ApplicationContext context: Context): DeepLookStore = DeepLookStore(context)

    @Provides
    @Singleton
    fun provideDeepLook(llm: LlmClient, store: DeepLookStore): DeepLookService = DeepLookService(llm, store)
}
