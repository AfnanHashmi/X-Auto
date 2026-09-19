package com.clipcut.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeletedPostDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DeletedPost)

    /** Every id ever logged, in any state: none of them may be offered for deletion again. */
    @Query("SELECT tweetId FROM deleted_posts")
    suspend fun allIds(): List<String>

    /** Rows for a failed request that provably did not delete anything (rolled back, never a real deletion). */
    @Query("DELETE FROM deleted_posts WHERE tweetId = :id AND outcome = 'IN_FLIGHT'")
    suspend fun discardInFlight(id: String)

    @Query("SELECT COUNT(*) FROM deleted_posts WHERE deletedAtEpochMs >= :sinceEpochMs")
    suspend fun countSince(sinceEpochMs: Long): Int

    @Query("SELECT * FROM deleted_posts ORDER BY deletedAtEpochMs DESC")
    fun observeAll(): Flow<List<DeletedPost>>
}
