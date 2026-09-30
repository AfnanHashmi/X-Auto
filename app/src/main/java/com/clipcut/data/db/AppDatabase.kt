package com.clipcut.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CaptionTemplate::class, UndoEntry::class, ClipRecord::class, DeletedPost::class],
    version = 10,
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
        /** Adds the daily-link broadcast tables: where it goes, and what happened each morning. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `broadcast_targets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`platform` TEXT NOT NULL, `chatId` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `broadcast_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`runAtEpochMs` INTEGER NOT NULL, `platform` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, `detail` TEXT NOT NULL, `link` TEXT NOT NULL, " +
                        "`manual` INTEGER NOT NULL)",
                )
            }
        }

        /** Removes the Daily Link feature's tables (v6 phones have them, empty or not). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `broadcast_targets`")
                db.execSQL("DROP TABLE IF EXISTS `broadcast_log`")
            }
        }

        // The current schema has `account` on clip_records (one clip can go to more than one media account,
        // and each account keeps its own duplicate-post record). Three phones can reach it:
        //   v7  never had the column                          -> add it
        //   v8  already has it (the first two-account build)  -> nothing to change
        //   v9  had it removed again (single-account build)   -> add it back
        // In every case existing rows become DK's, and rows already stored for another account are left alone.
        private const val ADD_ACCOUNT = "ALTER TABLE clip_records ADD COLUMN account TEXT NOT NULL DEFAULT 'DK'"

        val MIGRATION_7_10 = object : Migration(7, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(ADD_ACCOUNT)
            }
        }

        val MIGRATION_8_10 = object : Migration(8, 10) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(ADD_ACCOUNT)
            }
        }
    }
}
