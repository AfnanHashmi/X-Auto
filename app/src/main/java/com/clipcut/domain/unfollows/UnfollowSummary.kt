package com.clipcut.domain.unfollows

import com.clipcut.data.db.Unfollow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The numbers on the home card: today, in the phone's own time zone. */
data class UnfollowSummary(val unfollowedToday: Int, val kickedToday: Int, val wouldKickToday: Int, val waiting: Int) {
    companion object {
        val EMPTY = UnfollowSummary(0, 0, 0, 0)

        fun of(rows: List<Unfollow>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): UnfollowSummary {
            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            return UnfollowSummary(
                unfollowedToday = rows.count { day(it.receivedAtEpochMs) == today },
                kickedToday = rows.count { GcStatus.isKicked(it.gcStatus) && day(it.gcUpdatedAtEpochMs ?: it.receivedAtEpochMs) == today },
                wouldKickToday = rows.count { it.gcStatus == GcStatus.WOULD_KICK && day(it.gcUpdatedAtEpochMs ?: it.receivedAtEpochMs) == today },
                waiting = rows.count { it.gcStatus == GcStatus.PENDING },
            )
        }
    }
}
