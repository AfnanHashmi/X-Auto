package com.clipcut.domain.gcprune

/**
 * Decides, for every name you pasted, what can happen to that person. Pure: no network, no clock.
 *
 * Every rule fails toward NOT removing. Only someone who was found, is in the group and is not you, an admin
 * or on the whitelist can ever be offered for removal; the rest are shown, greyed out, with the reason.
 */
object PrunePlanner {
    sealed interface Status {
        /** In the group and does not follow the main account: ticked by default (when the follow check is trusted). */
        data object Removable : Status

        /** In the group but follows the main account: shown with a warning and NOT ticked, since it is probably a slip. */
        data object FollowsYou : Status
        data object NotInGroup : Status
        data class Protected(val why: String) : Status
        data class NotFound(val reason: String) : Status
    }

    /** One pasted name and what became of it. [userId] is null when the account was not found. */
    data class Row(val requested: String, val userId: String?, val handle: String?, val name: String?, val status: Status) {
        /** Only these can be ticked and removed. */
        val selectable: Boolean get() = userId != null && (status is Status.Removable || status is Status.FollowsYou)
    }

    /** Never removable, whatever the plan or a caller says: you, a group admin, or someone you whitelisted. */
    fun isProtected(userId: String, admins: Set<String>, ownIds: Set<String>, whitelist: Set<String>): Boolean =
        userId in ownIds || userId in admins || userId in whitelist

    fun protectionReason(userId: String, admins: Set<String>, ownIds: Set<String>, whitelist: Set<String>): String? = when {
        userId in ownIds -> "one of your own accounts"
        userId in admins -> "a group admin"
        userId in whitelist -> "on your whitelist"
        else -> null
    }

    /**
     * One row per pasted name, in the order pasted. Two names that turn out to be the same account give one row,
     * so nobody can be removed twice.
     */
    fun classify(
        lookups: List<NameLookup>,
        members: Set<String>,
        admins: Set<String>,
        ownIds: Set<String>,
        whitelist: Set<String>,
    ): List<Row> {
        val seen = HashSet<String>()
        val rows = ArrayList<Row>()
        for (l in lookups) {
            val f = l.found
            if (f == null) {
                rows += Row(l.requested, null, null, null, Status.NotFound(l.notFoundReason ?: "not found"))
                continue
            }
            if (!seen.add(f.id)) continue
            val status = protectionReason(f.id, admins, ownIds, whitelist)?.let { Status.Protected(it) }
                ?: when {
                    f.id !in members -> Status.NotInGroup
                    f.followsMain -> Status.FollowsYou
                    else -> Status.Removable
                }
            rows += Row(l.requested, f.id, f.username, f.name, status)
        }
        return rows
    }
}
