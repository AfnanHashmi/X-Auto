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
 * DK's keys are the original `X_MEDIA_*` entries; BB's are `X_BB_*`.
 */
enum class MediaAccount(val label: String) {
    DK("DK"),
    BB("BB"),
    ;

    val handle: String
        get() = when (this) {
            DK -> "dk_account"
            BB -> BuildConfig.X_BB_HANDLE.trim().trimStart('@')
        }

    val credentials: XCredentials
        get() = when (this) {
            DK -> XCredentials(
                BuildConfig.X_MEDIA_CONSUMER_KEY, BuildConfig.X_MEDIA_CONSUMER_SECRET,
                BuildConfig.X_MEDIA_ACCESS_TOKEN, BuildConfig.X_MEDIA_ACCESS_SECRET,
            )
            BB -> XCredentials(
                BuildConfig.X_BB_CONSUMER_KEY, BuildConfig.X_BB_CONSUMER_SECRET,
                BuildConfig.X_BB_ACCESS_TOKEN, BuildConfig.X_BB_ACCESS_SECRET,
            )
        }

    /** All four keys and the expected handle are present; without them the account cannot be used at all. */
    val isConfigured: Boolean get() = credentials.isComplete && handle.isNotBlank()
}

/**
 * Which account to use given what was stored. An unknown value, or an account whose keys are missing,
 * falls back to [MediaAccount.DK]: never to an account that cannot authenticate.
 */
fun resolveMediaAccount(stored: String?, isConfigured: (MediaAccount) -> Boolean): MediaAccount {
    val wanted = MediaAccount.entries.firstOrNull { it.name == stored } ?: MediaAccount.DK
    return if (isConfigured(wanted)) wanted else MediaAccount.DK
}
