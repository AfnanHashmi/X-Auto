package com.clipcut.ui.gcprune

import com.clipcut.domain.gcprune.ExecSettings
import com.clipcut.domain.gcprune.RunReport
import com.clipcut.domain.gcprune.ScanEstimate
import com.clipcut.domain.gcprune.ScanReady

enum class GcPhase { INPUT, SCANNING, CONFIRM_COST, REVIEW, REMOVING, DONE }

data class GcPruneUiState(
    val phase: GcPhase = GcPhase.INPUT,
    val link: String = "",
    /** Why a scan was refused or aborted, or what is wrong with the link. Shown on the first step. */
    val message: String? = null,
    val followersFetched: Int = 0,
    /** Set while the user is asked to approve what a scan will cost; nothing has been read yet. */
    val estimate: ScanEstimate? = null,
    val pendingGroupId: String? = null,
    val scan: ScanReady? = null,
    val selected: Set<String> = emptySet(),
    /** Removal is off until this is switched on; with it off the screen is a preview and nothing can be removed. */
    val allowRemoval: Boolean = false,
    val capText: String = ExecSettings.DEFAULT_CAP.toString(),
    val delayText: String = (ExecSettings.DEFAULT_DELAY_MS / 1000).toString(),
    val typedId: String = "",
    val confirmOpen: Boolean = false,
    val report: RunReport? = null,
    val whitelist: Set<String> = emptySet(),
    val whitelistInput: String = "",
    val whitelistMessage: String? = null,
) {
    val cap: Int? get() = capText.trim().toIntOrNull()?.takeIf { it in 1..ExecSettings.MAX_CAP }

    /** Seconds typed on screen, kept between 1 second and 10 minutes. */
    val delayMs: Long? get() = delayText.trim().toLongOrNull()?.takeIf { it in 1..600 }?.times(1000)

    val selectedCount: Int get() = scan?.candidates?.count { it.userId in selected } ?: 0

    /** Every condition for removing anyone. If any one is false the button is disabled. */
    val canRemove: Boolean
        get() {
            val s = scan ?: return false
            return phase == GcPhase.REVIEW && allowRemoval && typedId == s.groupId && selectedCount > 0 && cap != null && delayMs != null
        }
}
