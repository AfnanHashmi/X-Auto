package com.clipcut.ui.gcprune

import com.clipcut.domain.gcprune.ExecSettings
import com.clipcut.domain.gcprune.GcLink
import com.clipcut.domain.gcprune.HandleList
import com.clipcut.domain.gcprune.LookupReady
import com.clipcut.domain.gcprune.PrunePlanner
import com.clipcut.domain.gcprune.RunReport
import com.clipcut.domain.gcprune.XPricing

enum class GcPhase { INPUT, LOOKING_UP, REVIEW, REMOVING, DONE }

data class GcPruneUiState(
    val phase: GcPhase = GcPhase.INPUT,
    val link: String = "",
    /** The pasted usernames, exactly as typed. */
    val namesText: String = "",
    /** Why a lookup was refused or aborted, or what is wrong with the input. Shown on the first step. */
    val message: String? = null,
    val lookup: LookupReady? = null,
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
    val parsed: HandleList.Parsed = HandleList.parse(namesText)

    /** What looking these names up will cost, shown on the button before anything is read. */
    val lookupCostUsd: Double get() = XPricing.lookupCostUsd(parsed.valid.size)

    val canLookUp: Boolean get() = GcLink.parseGroupId(link) != null && parsed.valid.isNotEmpty() && !parsed.tooMany

    val cap: Int? get() = capText.trim().toIntOrNull()?.takeIf { it in 1..ExecSettings.MAX_CAP }

    /** Seconds typed on screen, kept between 1 second and 10 minutes. */
    val delayMs: Long? get() = delayText.trim().toLongOrNull()?.takeIf { it in 1..600 }?.times(1000)

    private val ticked: List<PrunePlanner.Row> get() = lookup?.rows?.filter { it.selectable && it.userId in selected }.orEmpty()

    val selectedCount: Int get() = ticked.size

    /** Ticked people who follow you: ticking them is allowed, but the final dialog calls them out. */
    val tickedFollowers: Int get() = ticked.count { it.status is PrunePlanner.Status.FollowsYou }

    /** Every condition for removing anyone. If any one is false the button is disabled. */
    val canRemove: Boolean
        get() {
            val l = lookup ?: return false
            return phase == GcPhase.REVIEW && allowRemoval && typedId == l.groupId && selectedCount > 0 && cap != null && delayMs != null
        }
}
