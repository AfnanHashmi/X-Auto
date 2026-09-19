package com.clipcut.domain.api.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MainPostText
import com.clipcut.domain.api.MediaRef
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * "Posts" to the main account by copying the post text (caption + embed link) to the clipboard, so
 * the user pastes and posts it themselves. Nothing is sent to X from this app for the main account.
 * Idempotent, so unlike a real post it can safely run again (e.g. after the app is restored).
 */
class ClipboardMainAccountApi @Inject constructor(
    @ApplicationContext private val context: Context,
) : MainAccountApi {
    override suspend fun post(caption: String, mediaRef: MediaRef): Result<Unit> = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ClipCut post", MainPostText.build(caption, mediaRef.url)))
    }
}
