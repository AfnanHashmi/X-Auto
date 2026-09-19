package com.clipcut.ui.deletion

import com.clipcut.domain.deletion.DeletionCandidate
import com.clipcut.domain.deletion.DeletionPreset
import com.clipcut.domain.deletion.RunResult
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
    val result: RunResult? = null,
    /** Shown on the PICK screen when a find failed, verbatim from X where there is one. */
    val error: String? = null,
) {
    val selectedCount: Int get() = rows.count { it.selected && it.status == RowStatus.PENDING }
    val canFind: Boolean
        get() = phase == DeletionPhase.PICK && (preset != DeletionPreset.CUSTOM || (customFrom != null && customTo != null))
    val settledCount: Int get() = rows.count { it.status != RowStatus.PENDING }
}
