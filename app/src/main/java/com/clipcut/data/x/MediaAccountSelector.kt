package com.clipcut.data.x

import android.content.Context
import androidx.core.content.edit
import com.clipcut.domain.api.MediaAccount
import com.clipcut.domain.api.resolveMediaAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which media account is selected (DK or BB). The choice survives restarts, but it is read
 * once at the start of each operation (an upload, a deletion scan) and that operation keeps its account
 * to the end, so switching can never split one clip's upload across two accounts.
 */
@Singleton
class MediaAccountSelector @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("media_account", Context.MODE_PRIVATE)

    var active: MediaAccount
        get() = resolveMediaAccount(prefs.getString(KEY_ACTIVE, null)) { it.isConfigured }
        set(value) {
            require(value.isConfigured) { "${value.label} has no API keys in local.properties" }
            prefs.edit { putString(KEY_ACTIVE, value.name) }
        }

    private companion object {
        const val KEY_ACTIVE = "active"
    }
}
