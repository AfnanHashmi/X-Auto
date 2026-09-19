package com.clipcut.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CaptionTemplate::class, UndoEntry::class, ClipRecord::class, DeletedPost::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun captionTemplateDao(): CaptionTemplateDao
    abstract fun undoEntryDao(): UndoEntryDao
    abstract fun clipRecordDao(): ClipRecordDao
    abstract fun deletedPostDao(): DeletedPostDao

    companion object {
        /** Adds `mediaRefUrl`, so a restored Caption screen can restore its media reference in full. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clip_records ADD COLUMN mediaRefUrl TEXT")
            }
        }

        /** Adds `uploadAttempted`, the crash-mid-upload detection flag. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clip_records ADD COLUMN uploadAttempted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds `postAttempted`, the crash-mid-post detection flag. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clip_records ADD COLUMN postAttempted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds `deleted_posts`, the Trigger Deletion log. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_posts` (`tweetId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`bbNumber` INTEGER, `postedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER NOT NULL, " +
                        "`outcome` TEXT NOT NULL, `runId` TEXT NOT NULL, PRIMARY KEY(`tweetId`))",
                )
            }
        }
    }
}
