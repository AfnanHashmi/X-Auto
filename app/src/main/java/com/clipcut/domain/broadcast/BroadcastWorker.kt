package com.clipcut.domain.broadcast

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Does the actual sending, off the alarm's very short leash: a BroadcastReceiver gets about ten
 * seconds, and on this WiFi a single request has been measured taking twenty.
 *
 * It never returns [Result.retry]. A retry cannot tell "the message did not send" from "the reply
 * never arrived", and guessing wrong posts the link into a group twice; the run is logged either
 * way, and the next chance is tomorrow morning.
 */
@HiltWorker
class BroadcastWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val service: BroadcastService,
    private val scheduler: BroadcastScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            service.run(manual = false)
        } finally {
            // Arm tomorrow even if today went wrong, so one bad morning does not end the schedule.
            scheduler.schedule()
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "daily-broadcast"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.KEEP, // a second alarm while one is running must not double-send
                OneTimeWorkRequestBuilder<BroadcastWorker>().build(),
            )
        }
    }
}
