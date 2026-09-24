package com.clipcut.ui.deletion

import com.clipcut.domain.api.MediaAccount
import com.clipcut.domain.deletion.DeletionCandidate
import com.clipcut.domain.deletion.DeletionPreset
import com.clipcut.domain.deletion.RunResult
import com.clipcut.domain.deletion.RunStop
import java.time.LocalDate

enum class DeletionPhase { PICK, FINDING, REVIEW, DELETING, DONE }

enum class RowStatus { PENDING, DELETED, ALREADY_GONE }

data class ReviewRow(
    val candidate: DeletionCandidate,
    val selected: Boolean = true,
    val status: RowStatus = RowStatus.PENDING,
)

data class DeletionUiState(
    val phase: DeletionPhase = DeletionPhase.PICK,
    /** Fixed when the screen opens: the account whose posts are listed, and the only one this run can touch. */
    val account: MediaAccount = MediaAccount.DK,
    val preset: DeletionPreset = DeletionPreset.LAST_1H,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
    val handle: String = "",
    val windowLabel: String = "",
    val rows: List<ReviewRow> = emptyList(),
    val pages: Int = 0,
    val scanned: Int = 0,
    val truncated: Boolean = false,
    val confirmVisible: Boolean = false,
    /** Progress of the run in flight only -- posts settled by an earlier run must not count towards it. */
    val runTotal: Int = 0,
    val runSettled: Int = 0,
    val result: RunResult? = null,
    /** Shown on the PICK screen when a find failed, verbatim from X where there is one. */
    val error: String? = null,
) {
    /** Still to delete: a settled row is never counted again, however it was settled. */
    val selectedCount: Int get() = rows.count { it.selected && it.status == RowStatus.PENDING }
    val canFind: Boolean
        get() = phase == DeletionPhase.PICK && (preset != DeletionPreset.CUSTOM || (customFrom != null && customTo != null))

    /** When a stopped run may be continued (epoch seconds), or null if it may be continued now. */
    val resumeAllowedAtEpochSec: Long?
        get() = (result?.stop as? RunStop.RateLimited)?.resetEpochSec
}
