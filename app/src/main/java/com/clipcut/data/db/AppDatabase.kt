package com.clipcut.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CaptionTemplate::class, UndoEntry::class, ClipRecord::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun captionTemplateDao(): CaptionTemplateDao
    abstract fun undoEntryDao(): UndoEntryDao
    abstract fun clipRecordDao(): ClipRecordDao

    companion object {
        /** Adds `mediaRefUrl`, so a restored Caption screen can restore its media reference in full. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clip_records ADD COLUMN mediaRefUrl TEXT")
            }
        }
    }
}
