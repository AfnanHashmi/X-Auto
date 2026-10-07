package com.clipcut.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One person who unfollowed the main account, as reported by the tracker. [eventUuid] is X's own id for the
 * event, so storing the same event twice (a re-fetch, a replay) is a no-op. [seq] is the tracker's counter.
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
)

@Dao
interface UnfollowDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<Unfollow>): List<Long>

    /** The newest tracker counter already stored: the next fetch asks for everything after it. */
    @Query("SELECT MAX(seq) FROM unfollows")
    suspend fun maxSeq(): Long?

    @Query("SELECT COUNT(*) FROM unfollows")
    suspend fun count(): Int

    @Query("SELECT * FROM unfollows ORDER BY receivedAtEpochMs DESC, seq DESC")
    fun observeAll(): Flow<List<Unfollow>>
}
