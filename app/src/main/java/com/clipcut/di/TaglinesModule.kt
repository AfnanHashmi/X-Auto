package com.clipcut.di

import android.content.Context
import com.clipcut.domain.llm.LlmClient
import com.clipcut.domain.history.ContestantStore
import com.clipcut.domain.taglines.RealTrendSearchApi
import com.clipcut.domain.taglines.TrendCheckService
import com.clipcut.domain.taglines.TrendCheckStore
import com.clipcut.domain.taglines.TrendSearchApi
import com.clipcut.domain.taglines.judgeSentiment
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
    @Binds abstract fun bindTrendSearch(impl: RealTrendSearchApi): TrendSearchApi
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
    fun provideTrendStore(@ApplicationContext context: Context): TrendCheckStore = TrendCheckStore(context)

    @Provides
    @Singleton
    fun provideTrendCheck(
        counts: TaglinesApi,
        search: TrendSearchApi,
        llm: LlmClient,
        store: TrendCheckStore,
        contestants: ContestantStore,
    ): TrendCheckService = TrendCheckService(
        counts, search, store, contestants = { contestants.knownNames() },
        judge = { posts, names -> judgeSentiment(llm, posts, names) },
    )
}
