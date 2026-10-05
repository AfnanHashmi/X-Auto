package com.clipcut.domain.gcprune

import com.clipcut.data.x.MediaClients
import com.clipcut.data.x.XApiClient
import com.clipcut.data.x.XApiException
import com.clipcut.data.x.XRateLimit
import com.clipcut.data.x.XReply
import com.clipcut.domain.api.MediaAccount
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named

/**
 * [GcPruneApi] over the real X API. Who does what:
 *  - the MAIN account's keys read its followers and answer "does this person follow me";
 *  - RW's keys (@rw_account) read the group and remove members, because RW is the group's admin.
 * Keys come from BuildConfig (local.properties) via the existing clients and are never logged.
 */
class RealGcPruneApi @Inject constructor(
    @Named("main") private val main: XApiClient,
    private val media: MediaClients,
) : GcPruneApi {
    @Volatile private var removerId: String? = null

    private val remover: XApiClient get() = media.clientFor(MediaAccount.RW)

    private fun rate(r: XRateLimit) = RateInfo(r.remaining, r.resetEpochSec)

    /** Runs [block], turning X's failures into [GcApiException] so the service sees one kind of error. */
    private suspend fun <T> guarded(block: suspend () -> XReply, parse: (String) -> T): Reply<T> {
        val reply = try {
            block()
        } catch (e: XApiException) {
            val reset = e.rateLimit?.resetAt(System.currentTimeMillis() / 1000)
            throw GcApiException(e.message.orEmpty(), e.httpCode, reset, cause = e)
        } catch (e: IOException) {
            throw GcApiException("no reply from X: ${e.message}", null, noReply = true, cause = e)
        }
        return Reply(parse(reply.body), rate(reply.rateLimit))
    }

    override suspend fun mainAccount(): Reply<SelfInfo> = guarded({ main.getMeWithFollowers() }, ::parseSelf)

    override suspend fun removerAccount(): Reply<SelfInfo> {
        val reply = guarded({ remover.getMeWithFollowers() }, ::parseSelf)
        // The keys must belong to the account that is meant to be removing people.
        check(reply.value.username.equals(MediaAccount.RW.handle, ignoreCase = true)) {
            "The RW keys belong to @${reply.value.username}, not @${MediaAccount.RW.handle}"
        }
        removerId = reply.value.id
        return reply
    }

    override suspend fun otherOwnAccountIds(): Set<String> =
        MediaAccount.entries.filter { it != MediaAccount.RW && it.isConfigured }.map { account ->
            guarded({ media.clientFor(account).getMeWithFollowers() }, ::parseSelf).value.id
        }.toSet()

    override suspend fun readGroup(groupId: String): Reply<GcConversation> =
        guarded({ remover.getChatConversation(groupId) }) { parseConversation(it, groupId) }

    override suspend fun followersPage(userId: String, token: String?): Reply<FollowersPage> =
        guarded({ main.getFollowersPage(userId, token) }, ::parseFollowersPage)

    override suspend fun lookupUsers(ids: List<String>): Reply<List<GcUserInfo>> =
        guarded({ main.getUsers(ids) }, ::parseUsers)

    override suspend fun lookupHandle(handle: String): Reply<GcUserInfo?> =
        guarded({ main.getUserByUsername(handle.trim().trimStart('@')) }, ::parseSingleUser)

    override suspend fun followCheck(userId: String): Reply<FollowCheck> =
        guarded({ main.getConnectionStatus(userId) }) { parseFollowCheck(it, userId) }

    override suspend fun removeMember(groupId: String, userId: String): Reply<RemoveOutcome> {
        // Passing the remover's own id would make it LEAVE the group; refuse that here, whatever the caller asked.
        val self = removerId ?: removerAccount().value.id
        check(userId != self) { "Refusing to remove the account that is doing the removing" }
        return guarded({ remover.removeChatMembers(groupId, listOf(userId)) }, ::parseRemoveResponse)
    }
}
