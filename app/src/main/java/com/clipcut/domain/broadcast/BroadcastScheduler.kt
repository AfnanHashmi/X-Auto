package com.clipcut.domain.broadcast

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires the daily broadcast at the configured time **in India**, not in whatever zone the phone
 * happens to be in, so "7am IST" stays 7am IST while travelling.
 *
 * AlarmManager rather than a periodic WorkManager job because periodic work is only approximately
 * periodic -- it drifts and batches, which is fine for syncing and wrong for "every morning at 7".
 * The alarm only wakes us up; the sending itself is handed to a Worker that can outlive it.
 */
@Singleton
class BroadcastScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: BroadcastSettings,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Arms (or re-arms) tomorrow morning's alarm; a no-op when the feature is switched off. */
    fun schedule() {
        if (!settings.enabled) {
            cancel()
            return
        }
        val at = nextRunMs()
        val pending = pendingIntent()
        // setExact* survives Doze. Without the exact-alarm permission we fall back to the inexact
        // form, which may run late by a few minutes -- late is acceptable, silent is not.
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel() = alarms.cancel(pendingIntent())

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /** The next [BroadcastSettings.hour]:[BroadcastSettings.minute] in India that is still ahead of us. */
    fun nextRunMs(nowMs: Long = System.currentTimeMillis()): Long =
        nextDailyRunMs(nowMs, settings.hour, settings.minute, INDIA)

    fun nextRunAt(nowMs: Long = System.currentTimeMillis()): ZonedDateTime =
        Instant.ofEpochMilli(nextRunMs(nowMs)).atZone(ZoneId.systemDefault())

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BroadcastAlarmReceiver::class.java).setAction(ACTION_DAILY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")
        const val ACTION_DAILY = "com.clipcut.DAILY_BROADCAST"
        private const val REQUEST_CODE = 7001
    }
}

/**
 * The next wall-clock [hour]:[minute] in [zone] that is strictly after [nowMs]. Kept out of the class
 * so the one piece of arithmetic this feature depends on can be tested without Android.
 */
fun nextDailyRunMs(nowMs: Long, hour: Int, minute: Int, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    if (!next.isAfter(now)) next = next.plusDays(1)
    return next.toInstant().toEpochMilli()
}
