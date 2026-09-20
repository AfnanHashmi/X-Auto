package com.clipcut

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * WorkManager's own initializer is removed in the manifest so it starts from here instead, with the
 * Hilt factory that knows how to build [com.clipcut.domain.broadcast.BroadcastWorker] with its
 * dependencies injected.
 */
@HiltAndroidApp
class ClipCutApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
