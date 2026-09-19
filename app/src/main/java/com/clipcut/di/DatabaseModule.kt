package com.clipcut.di

import android.content.Context
import androidx.room.Room
import com.clipcut.data.db.AppDatabase
import com.clipcut.data.db.CaptionTemplateDao
import com.clipcut.data.db.ClipRecordDao
import com.clipcut.data.db.UndoEntryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "clipcut.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

    @Provides
    fun provideCaptionTemplateDao(db: AppDatabase): CaptionTemplateDao = db.captionTemplateDao()

    @Provides
    fun provideUndoEntryDao(db: AppDatabase): UndoEntryDao = db.undoEntryDao()

    @Provides
    fun provideClipRecordDao(db: AppDatabase): ClipRecordDao = db.clipRecordDao()
}
