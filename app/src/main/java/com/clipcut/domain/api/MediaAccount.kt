package com.clipcut.domain.api

import com.clipcut.BuildConfig

/** One set of OAuth 1.0a credentials for one X account. */
data class XCredentials(
    val consumerKey: String,
    val consumerSecret: String,
    val accessToken: String,
    val accessSecret: String,
) {
    val isComplete: Boolean
        get() = consumerKey.isNotBlank() && consumerSecret.isNotBlank() && accessToken.isNotBlank() && accessSecret.isNotBlank()
}

/**
 * The X accounts a clip can be posted to, and that Trigger Deletion can clean up. Exactly one is
 * active at a time (see `MediaAccountSelector`).
 *
 * [handle] is what the API must report for the keys in use. Every real request checks it, so keys that
 * were pasted under the wrong account fail loudly instead of quietly posting (or deleting) as the wrong one.
 * DK's keys are the original `X_MEDIA_*` entries; RW's are `X_RW_*`.
 */
enum class MediaAccount(val label: String) {
    DK("DK"),
    RW("RW"),
    ;

    val handle: String
        get() = when (this) {
            DK -> "dk_account"
            RW -> BuildConfig.X_RW_HANDLE.trim().trimStart('@')
        }

    /**
     * What this account's posts are captioned with, before the number ("BB LOGS" gives "BB LOGS - 014").
     * DK's is fixed. RW's comes from local.properties and has no default: a public account must never
     * post under a label that was only guessed.
     */
    val captionLabel: String
        get() = when (this) {
            DK -> "BB LOGS"
            RW -> BuildConfig.X_RW_CAPTION_LABEL.trim()
        }

    val credentials: XCredentials
        get() = when (this) {
            DK -> XCredentials(
                BuildConfig.X_MEDIA_CONSUMER_KEY, BuildConfig.X_MEDIA_CONSUMER_SECRET,
                BuildConfig.X_MEDIA_ACCESS_TOKEN, BuildConfig.X_MEDIA_ACCESS_SECRET,
            )
            RW -> XCredentials(
                BuildConfig.X_RW_CONSUMER_KEY, BuildConfig.X_RW_CONSUMER_SECRET,
                BuildConfig.X_RW_ACCESS_TOKEN, BuildConfig.X_RW_ACCESS_SECRET,
            )
        }

    /** All four keys, the expected handle and the caption label are present; without them the account cannot be used. */
    val isConfigured: Boolean get() = credentials.isComplete && handle.isNotBlank() && captionLabel.isNotBlank()
}

/** The caption of the [number]th post: label, a dash, then the number zero-padded to three digits. */
fun postCaption(label: String, number: Int): String = "%s - %03d".format(label, number)

/**
 * Which account to use given what was stored. An unknown value, or an account that is not fully set up,
 * falls back to [MediaAccount.DK]: never to an account that cannot authenticate.
 */
fun resolveMediaAccount(stored: String?, isConfigured: (MediaAccount) -> Boolean): MediaAccount {
    val wanted = MediaAccount.entries.firstOrNull { it.name == stored } ?: MediaAccount.DK
    return if (isConfigured(wanted)) wanted else MediaAccount.DK
}
