package com.clipcut.domain.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The 7am alarm: hands the work straight to [BroadcastWorker] and gets out of the way. */
@AndroidEntryPoint
class BroadcastAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: BroadcastSettings

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BroadcastScheduler.ACTION_DAILY) return
        if (!settings.enabled) return
        BroadcastWorker.enqueue(context)
    }
}

/** Alarms do not survive a reboot, so re-arm after one (and after an app update). */
@AndroidEntryPoint
class BroadcastBootReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: BroadcastScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> scheduler.schedule()
        }
    }
}
