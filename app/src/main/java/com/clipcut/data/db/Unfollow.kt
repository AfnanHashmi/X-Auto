package com.clipcut.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One person who unfollowed the main account, as reported by the tracker, plus what the group-chat automation
 * did about it. [eventUuid] is X's own id for the event, so storing the same event twice is a no-op.
 * [version] is the tracker's change counter: the row is replaced whenever the follow-up changes.
 *
 * [gcStatus] is one of: pending, would_kick, in_flight, kicked, failed, not_in_gc, refollowed, protected,
 * unverified, skipped, not_tracked.
 */
@Entity(tableName = "unfollows")
data class Unfollow(
    @PrimaryKey val eventUuid: String,
    val seq: Long,
    val userId: String,
    val username: String?,
    val name: String?,
    val followersCount: Int?,
    val followingCount: Int?,
    val receivedAtEpochMs: Long,
    val fetchedAtEpochMs: Long,
    val gcStatus: String = "not_tracked",
    val gcDetail: String? = null,
    /** When the group follow-up is (or was) due: the re-check happens then. */
    val gcDueAtEpochMs: Long? = null,
    val gcUpdatedAtEpochMs: Long? = null,
    val version: Long = 0,
)

@Dao
interface UnfollowDao {
    /** Replaces a row when its follow-up changed; a re-fetch of the same state changes nothing. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<Unfollow>)

    /** The newest tracker change already stored: the next fetch asks for everything after it. */
    @Query("SELECT MAX(version) FROM unfollows")
    suspend fun maxVersion(): Long?

    @Query("SELECT COUNT(*) FROM unfollows")
    suspend fun count(): Int

    @Query("SELECT * FROM unfollows ORDER BY receivedAtEpochMs DESC, seq DESC")
    fun observeAll(): Flow<List<Unfollow>>
}
