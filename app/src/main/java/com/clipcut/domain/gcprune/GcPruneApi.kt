package com.clipcut.domain.gcprune

/** What X said about its rate limits on a reply. Both fields may be missing. */
data class RateInfo(val remaining: Int?, val resetEpochSec: Long?)

/** A parsed answer plus the rate-limit headers that came with it. */
data class Reply<T>(val value: T, val rate: RateInfo? = null)

/**
 * A call that failed in a way the service must react to.
 * [httpCode] is X's status (null when no reply came back at all, [noReply]). [resetAtEpochSec] is set on a 429.
 */
class GcApiException(
    message: String,
    val httpCode: Int?,
    val resetAtEpochSec: Long? = null,
    val noReply: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Everything the feature needs from X, in one seam so the whole safety logic can be tested against a
 * fake. The real one is [RealGcPruneApi]: handles are looked up as the MAIN account (so "follows me" means
 * follows main), and the group is read and members are removed as RW (@rw_account).
 */
interface GcPruneApi {
    /** The account people must follow. */
    suspend fun mainAccount(): Reply<SelfInfo>

    /** The account that removes people, with its identity verified against the handle it is meant to be. */
    suspend fun removerAccount(): Reply<SelfInfo>

    /** Ids of the user's other accounts (so none of them can ever be removed). */
    suspend fun otherOwnAccountIds(): Set<String>

    /** The @handle of an account that is known to follow the main account, used to prove the follow check works. */
    suspend fun knownFollowerHandle(): String?

    suspend fun readGroup(groupId: String): Reply<GcConversation>

    /** Up to 100 handles at once, each answered exactly once (see [parseUsersByName]). */
    suspend fun lookupHandles(handles: List<String>): Reply<List<NameLookup>>

    suspend fun removeMember(groupId: String, userId: String): Reply<RemoveOutcome>
}
