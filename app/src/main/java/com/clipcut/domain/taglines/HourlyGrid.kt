package com.clipcut.domain.taglines

const val HOUR_MS = 3_600_000L

/** A bucket X sent that does not fit the counted window. */
class GridException(message: String) : Exception(message)

/**
 * Every tagline's tweets per hour on one shared grid of whole UTC hours covering the counted window, oldest first: the
 * numbers behind the "Tweets per hour" graph. [series] is in the report's tagline order. An hour X sent no row for is 0.
 */
data class HourlyGrid(val startMs: Long, val hours: Int, val series: List<List<Long>>) {
    fun hourStart(i: Int): Long = startMs + i * HOUR_MS
}

/**
 * Builds the grid. X starts its first bucket at the exact start of the window (part-way into an hour); a bucket belongs
 * to the hour it starts in. A bucket outside the window is an error, never dropped.
 */
fun hourlyGrid(report: CountReport): HourlyGrid {
    val first = Math.floorDiv(report.startMs, HOUR_MS) * HOUR_MS
    val last = Math.floorDiv(report.endMs, HOUR_MS) * HOUR_MS
    val hours = ((last - first) / HOUR_MS + 1).toInt()
    val end = first + hours * HOUR_MS
    val series = report.counts.map { t ->
        val grid = LongArray(hours)
        for (b in t.all.hours) {
            val hourStart = Math.floorDiv(b.startMs, HOUR_MS) * HOUR_MS
            if (hourStart < first || hourStart >= end) throw GridException("An hourly bucket of \"${t.tagline.take(30)}\" does not fit the 24-hour graph.")
            grid[((hourStart - first) / HOUR_MS).toInt()] += b.count
        }
        grid.toList()
    }
    return HourlyGrid(first, hours, series)
}
