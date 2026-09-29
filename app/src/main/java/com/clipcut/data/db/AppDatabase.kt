package com.clipcut.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CaptionTemplate::class, UndoEntry::class, ClipRecord::class, DeletedPost::class],
    version = 9,
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

        /**
         * Removes the second media account (BB) from the app. Builds v9 from v8 by rebuilding `clip_records`
         * without its `account` column (SQLite before 3.35 cannot drop a column, and minSdk is 26).
         *
         * Nothing is deleted. Rows for any account other than DK are first copied, with their `account`
         * column, into `archived_other_account_clips`, which no code reads. They cannot stay in
         * `clip_records`: with the column gone the same clip file could not be told apart, and a leftover BB
         * row would be found by the DK upload guard and treated as "this clip is already uploaded".
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `archived_other_account_clips` AS " +
                        "SELECT * FROM clip_records WHERE account <> 'DK'",
                )
                db.execSQL(
                    "CREATE TABLE `clip_records_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`filePath` TEXT NOT NULL, `startEpochMs` INTEGER NOT NULL, `endEpochMs` INTEGER NOT NULL, " +
                        "`mediaRefId` TEXT, `mediaRefUrl` TEXT, `posted` INTEGER NOT NULL, " +
                        "`postAttempted` INTEGER NOT NULL, `uploadAttempted` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO clip_records_new (id, filePath, startEpochMs, endEpochMs, mediaRefId, mediaRefUrl, " +
                        "posted, postAttempted, uploadAttempted) " +
                        "SELECT id, filePath, startEpochMs, endEpochMs, mediaRefId, mediaRefUrl, " +
                        "posted, postAttempted, uploadAttempted FROM clip_records WHERE account = 'DK'",
                )
                db.execSQL("DROP TABLE clip_records")
                db.execSQL("ALTER TABLE clip_records_new RENAME TO clip_records")
            }
        }

        /** A phone still on v7 (before BB existed) already has the v9 schema, so there is nothing to change. */
        val MIGRATION_7_9 = object : Migration(7, 9) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }
    }
}
