package com.clipcut.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CaptionTemplate::class, UndoEntry::class, ClipRecord::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun captionTemplateDao(): CaptionTemplateDao
    abstract fun undoEntryDao(): UndoEntryDao
    abstract fun clipRecordDao(): ClipRecordDao
}
