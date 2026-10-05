package com.clipcut.data.gcprune

import android.content.Context
import com.clipcut.domain.gcprune.WhitelistSource
import com.clipcut.domain.gcprune.encodeWhitelist
import com.clipcut.domain.gcprune.parseWhitelist
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `whitelist.json` in the app's private storage: user ids that are never removed. A missing file is an
 * empty whitelist; a file that exists but cannot be read is an ERROR, and it is never overwritten, so a
 * damaged whitelist can't silently become "nobody is protected".
 */
@Singleton
class WhitelistStore @Inject constructor(
    @ApplicationContext context: Context,
) : WhitelistSource {
    private val file = File(context.filesDir, "whitelist.json")
    private val lock = Mutex()

    override suspend fun load(): Set<String> = lock.withLock { withContext(Dispatchers.IO) { read() } }

    suspend fun add(userId: String): Set<String> = change { it + userId }

    suspend fun remove(userId: String): Set<String> = change { it - userId }

    private suspend fun change(update: (Set<String>) -> Set<String>): Set<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            val next = update(read())
            val tmp = File(file.parentFile, "whitelist.json.tmp")
            tmp.writeText(encodeWhitelist(next))
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "Could not save the whitelist" }
            }
            next
        }
    }

    private fun read(): Set<String> = if (file.exists()) parseWhitelist(file.readText()) else emptySet()
}
