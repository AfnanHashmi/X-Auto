package com.clipcut.di

import android.content.Context
import com.clipcut.domain.history.ContestantStore
import com.clipcut.domain.history.RunHistory
import com.clipcut.domain.taglines.TaglineStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HistoryModule {
    /**
     * The run history lives in the app's private files (history/). On first use it takes in the count the screen already
     * had saved, so nothing collected before the history existed is lost.
     */
    @Provides
    @Singleton
    fun provideRunHistory(@ApplicationContext context: Context, taglines: TaglineStore): RunHistory =
        RunHistory(File(context.filesDir, "history")).apply {
            if (isEmpty) {
                taglines.lastReport()?.let(::saveTagline)
            }
            prune()
        }

    @Provides
    @Singleton
    fun provideContestants(@ApplicationContext context: Context): ContestantStore = ContestantStore(context)
}
