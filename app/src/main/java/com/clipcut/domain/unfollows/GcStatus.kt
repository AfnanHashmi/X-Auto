package com.clipcut.domain.unfollows

/** How a row's group-chat follow-up is shown. [tone] drives the colour of the badge. */
enum class GcTone { Done, Waiting, Neutral, Warning }

data class GcBadge(val label: String, val tone: GcTone)

/** What the tracker's status words mean on screen. Pure, so every word has a test. */
object GcStatus {
    const val KICKED = "kicked"
    const val WOULD_KICK = "would_kick"
    const val PENDING = "pending"

    fun isKicked(status: String) = status == KICKED

    /** Statuses a person can still be protected from (their follow-up has not been acted on). */
    fun canProtect(status: String) = status == PENDING || status == WOULD_KICK

    /** [dueClock] is the local time the re-check happens, already formatted (only used while pending). */
    fun badge(status: String, dueClock: String?, kickedClock: String?): GcBadge = when (status) {
        KICKED -> GcBadge("Kicked out of the group" + (kickedClock?.let { " at $it" } ?: ""), GcTone.Done)
        WOULD_KICK -> GcBadge("Would be kicked (dry run)", GcTone.Waiting)
        PENDING -> GcBadge("Waiting" + (dueClock?.let { ", re-check at $it" } ?: ""), GcTone.Waiting)
        "in_flight" -> GcBadge("Removing now", GcTone.Waiting)
        "not_in_gc" -> GcBadge("Not in the group", GcTone.Neutral)
        "refollowed" -> GcBadge("Followed again, kept", GcTone.Neutral)
        "protected" -> GcBadge("Protected, kept", GcTone.Neutral)
        "unverified" -> GcBadge("Could not verify, kept", GcTone.Warning)
        "failed" -> GcBadge("Removal failed", GcTone.Warning)
        "skipped" -> GcBadge("Automation was off", GcTone.Neutral)
        "not_tracked" -> GcBadge("Before the group automation", GcTone.Neutral)
        else -> GcBadge(status, GcTone.Neutral)
    }
}
