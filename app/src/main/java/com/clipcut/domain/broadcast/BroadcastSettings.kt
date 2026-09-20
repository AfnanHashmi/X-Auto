package com.clipcut.domain.broadcast

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The daily broadcast's settings. Off until you turn it on: an app that posts to group chats on its
 * own should never start doing so just because it was installed.
 */
@Singleton
class BroadcastSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("broadcast", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** [LINK] is replaced with the freshly fetched link. Everything else is sent as written. */
    var template: String
        get() = prefs.getString(KEY_TEMPLATE, DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        set(value) = prefs.edit { putString(KEY_TEMPLATE, value) }

    var hour: Int
        get() = prefs.getInt(KEY_HOUR, 7)
        set(value) = prefs.edit { putInt(KEY_HOUR, value.coerceIn(0, 23)) }

    var minute: Int
        get() = prefs.getInt(KEY_MINUTE, 0)
        set(value) = prefs.edit { putInt(KEY_MINUTE, value.coerceIn(0, 59)) }

    fun message(link: String): String = template.replace(LINK, link)

    companion object {
        const val LINK = "{link}"
        const val DEFAULT_TEMPLATE = "Good morning! Today's live link:\n$LINK"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TEMPLATE = "template"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"
    }
}
