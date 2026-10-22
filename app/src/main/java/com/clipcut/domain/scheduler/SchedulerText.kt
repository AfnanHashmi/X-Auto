package com.clipcut.domain.scheduler

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

/** An ISO time from the laptop as "Fri 23 Oct, 01:00" in [zone]; the original text if it cannot be read. */
fun whenText(iso: String?, zone: ZoneId): String {
    if (iso.isNullOrBlank()) return "-"
    return try {
        OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(WHEN)
    } catch (e: java.time.format.DateTimeParseException) {
        iso
    }
}

/** What the laptop is doing, in plain words. */
fun stateText(s: SchedStatus): String = when (s.state) {
    "recording" -> "Recording"
    "stopping" -> "Stopping the recording"
    "uploading" -> "Uploading to R2"
    "shutting_down" -> "Shutting down soon"
    else -> if (!s.obsReachable) "OBS is closed" else if (s.streaming) "Idle · streaming, not recording" else "Idle"
}
