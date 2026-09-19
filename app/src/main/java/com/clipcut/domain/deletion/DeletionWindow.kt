package com.clipcut.domain.deletion

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How far back a deletion run looks. [CUSTOM] takes its dates from the picker instead of [now]. */
enum class DeletionPreset(val label: String, val hoursBack: Long?) {
    LAST_1H("Last 1 hour", 1),
    LAST_6H("Last 6 hours", 6),
    LAST_24H("Last 24 hours", 24),
    LAST_7D("Last 7 days", 24 * 7),
    CUSTOM("Custom range", null),
}

/** A half-open window [start, endExclusive) in absolute time. */
data class DeletionRange(val start: Instant, val endExclusive: Instant) {
    init {
        require(start < endExclusive) { "Window start must be before its end" }
    }

    operator fun contains(t: Instant): Boolean = t >= start && t < endExclusive
}

fun presetRange(preset: DeletionPreset, now: Instant): DeletionRange {
    val hours = requireNotNull(preset.hoursBack) { "CUSTOM has no preset range; use customRange()" }
    return DeletionRange(now.minusSeconds(hours * 3600), now)
}

/**
 * A custom range covers whole local days: from 00:00 on [fromDay] to 00:00 on the day after [toDay],
 * so picking the same day twice means that entire day. A single day picked on the calendar is the
 * common case, and "19th to 19th" meaning "nothing" would be a trap.
 */
fun customRange(fromDay: LocalDate, toDay: LocalDate, zone: ZoneId): DeletionRange {
    require(!toDay.isBefore(fromDay)) { "The end date is before the start date" }
    return DeletionRange(
        start = fromDay.atStartOfDay(zone).toInstant(),
        endExclusive = toDay.plusDays(1).atStartOfDay(zone).toInstant(),
    )
}
