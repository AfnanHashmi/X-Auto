package com.clipcut.domain.gcprune

/**
 * Decides who is a candidate for removal. Pure: no network, no clock, so every rule here is tested.
 *
 * Every rule fails toward NOT removing. A candidate must be absent from a follower list that is
 * provably nearly complete, and must not be you, an admin or on the whitelist.
 */
object PrunePlanner {
    /** The fetched follower list must hold at least this share of the count X reports. */
    const val MIN_FOLLOWER_COVERAGE = 0.97

    /** More than this share of the group flagged means something is wrong with the list, not the group. */
    const val MAX_CANDIDATE_SHARE = 0.5

    data class Input(
        val members: List<String>,
        val admins: Set<String>,
        val followers: Set<String>,
        val followersReported: Int,
        val ownIds: Set<String>,
        val whitelist: Set<String>,
    )

    data class Exempt(val own: Int, val admins: Int, val whitelisted: Int)

    sealed interface Result {
        data class Abort(val reason: String) : Result
        data class Ready(val candidates: List<String>, val exempt: Exempt, val followersFetched: Int) : Result
    }

    /** Never removable, whatever the plan or a caller says: you, a group admin, or someone you whitelisted. */
    fun isProtected(userId: String, admins: Set<String>, ownIds: Set<String>, whitelist: Set<String>): Boolean =
        userId in ownIds || userId in admins || userId in whitelist

    fun plan(input: Input): Result {
        val members = input.members.distinct()
        if (members.isEmpty()) return Result.Abort("The group has no members, so there is nothing to check.")
        if (input.followersReported <= 0) {
            return Result.Abort("X reports no followers for your account, so a follower list cannot be checked against it.")
        }
        val fetched = input.followers.size
        if (fetched.toDouble() < input.followersReported * MIN_FOLLOWER_COVERAGE) {
            return Result.Abort(
                "Only $fetched of the ${input.followersReported} followers X reports were fetched. " +
                    "An incomplete list would flag real followers, so nothing was checked.",
            )
        }
        var own = 0
        var admins = 0
        var whitelisted = 0
        val candidates = ArrayList<String>()
        for (id in members) {
            if (id in input.followers) continue
            when {
                id in input.ownIds -> own++
                id in input.admins -> admins++
                id in input.whitelist -> whitelisted++
                else -> candidates.add(id)
            }
        }
        if (candidates.size * 2 > members.size) {
            return Result.Abort(
                "${candidates.size} of ${members.size} members (over half) would be flagged. " +
                    "That is far more than a normal clean-up, so the follower list is more likely wrong than the group.",
            )
        }
        return Result.Ready(candidates, Exempt(own, admins, whitelisted), fetched)
    }
}
