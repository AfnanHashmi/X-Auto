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
 * fake. The real one is [RealGcPruneApi]: followers and per-person checks are made as the MAIN account,
 * and the group is read and members are removed as RW (@rw_account).
 */
interface GcPruneApi {
    /** The account whose followers count: its id and how many followers X says it has. */
    suspend fun mainAccount(): Reply<SelfInfo>

    /** The account that removes people, with its identity verified against the handle it is meant to be. */
    suspend fun removerAccount(): Reply<SelfInfo>

    /** Ids of the user's other accounts (so none of them can ever be a candidate). */
    suspend fun otherOwnAccountIds(): Set<String>

    suspend fun readGroup(groupId: String): Reply<GcConversation>

    /** One page of the main account's followers, following [token] from the previous page. */
    suspend fun followersPage(userId: String, token: String?): Reply<FollowersPage>

    /** Profiles for up to 100 ids; accounts that no longer exist are simply missing from the result. */
    suspend fun lookupUsers(ids: List<String>): Reply<List<GcUserInfo>>

    suspend fun lookupHandle(handle: String): Reply<GcUserInfo?>

    /** Does [userId] follow the main account right now? */
    suspend fun followCheck(userId: String): Reply<FollowCheck>

    suspend fun removeMember(groupId: String, userId: String): Reply<RemoveOutcome>
}
