package com.clipcut.ui.deletion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.domain.deletion.CandidateOutcome
import com.clipcut.domain.deletion.DeletionPreset
import com.clipcut.domain.deletion.DeletionRange
import com.clipcut.domain.deletion.DeletionService
import com.clipcut.domain.deletion.RunResult
import com.clipcut.domain.deletion.RunStop
import com.clipcut.domain.deletion.customRange
import com.clipcut.domain.deletion.presetRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

/**
 * Two-phase flow: FIND (read-only) -> REVIEW -> explicit confirm -> DELETE. Nothing destructive is
 * reachable from the PICK/FINDING phases, and [confirmDelete] only ever sees rows the user has
 * reviewed. All safety rules themselves live in [DeletionService].
 */
@HiltViewModel
class DeletionViewModel @Inject constructor(
    private val service: DeletionService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DeletionUiState())
    val uiState: StateFlow<DeletionUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var lastFindAtMs = 0L
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun selectPreset(preset: DeletionPreset) {
        if (_uiState.value.phase != DeletionPhase.PICK) return
        _uiState.update { it.copy(preset = preset, error = null) }
    }

    fun setCustomRange(from: LocalDate, to: LocalDate) {
        _uiState.update { it.copy(preset = DeletionPreset.CUSTOM, customFrom = from, customTo = to, error = null) }
    }

    private fun currentRange(now: Instant): DeletionRange {
        val s = _uiState.value
        return if (s.preset == DeletionPreset.CUSTOM) {
            customRange(requireNotNull(s.customFrom), requireNotNull(s.customTo), zone)
        } else {
            presetRange(s.preset, now)
        }
    }

    fun find() {
        val s = _uiState.value
        if (!s.canFind) return
        // Repeated scans burn the same X quota the deletes need.
        val sinceLast = System.currentTimeMillis() - lastFindAtMs
        if (sinceLast < FIND_COOLDOWN_MS) {
            _uiState.update { it.copy(error = "Wait ${(FIND_COOLDOWN_MS - sinceLast) / 1000 + 1}s before scanning again.") }
            return
        }
        val now = Instant.now()
        val range = try {
            currentRange(now)
        } catch (e: IllegalArgumentException) {
            _uiState.update { it.copy(error = e.message) }
            return
        }
        lastFindAtMs = System.currentTimeMillis()
        _uiState.update { it.copy(phase = DeletionPhase.FINDING, error = null, pages = 0, scanned = 0) }
        job = viewModelScope.launch {
            try {
                val found = service.find(range, now) { pages, scanned ->
                    _uiState.update { it.copy(pages = pages, scanned = scanned) }
                }
                _uiState.update {
                    it.copy(
                        phase = DeletionPhase.REVIEW,
                        handle = found.handle,
                        rows = found.candidates.map { c -> ReviewRow(c) },
                        pages = found.pages,
                        scanned = found.scanned,
                        truncated = found.truncated,
                        windowLabel = "${label(range.start)} - ${label(range.endExclusive)}",
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(phase = DeletionPhase.PICK) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(phase = DeletionPhase.PICK, error = "Could not scan: ${e.message}") }
            }
        }
    }

    fun cancelFind() {
        if (_uiState.value.phase == DeletionPhase.FINDING) job?.cancel()
    }

    fun toggle(id: String) = _uiState.update { s ->
        s.copy(rows = s.rows.map { if (it.candidate.id == id && it.status == RowStatus.PENDING) it.copy(selected = !it.selected) else it })
    }

    fun selectAll(selected: Boolean) = _uiState.update { s ->
        s.copy(rows = s.rows.map { if (it.status == RowStatus.PENDING) it.copy(selected = selected) else it })
    }

    fun requestConfirm() {
        if (_uiState.value.selectedCount > 0 && _uiState.value.phase == DeletionPhase.REVIEW) {
            _uiState.update { it.copy(confirmVisible = true) }
        }
    }

    fun dismissConfirm() = _uiState.update { it.copy(confirmVisible = false) }

    fun confirmDelete() {
        val s = _uiState.value
        if (s.phase != DeletionPhase.REVIEW || !s.confirmVisible) return
        val queue = s.rows.filter { it.selected && it.status == RowStatus.PENDING }.map { it.candidate }
        if (queue.isEmpty()) return
        _uiState.update {
            it.copy(
                confirmVisible = false,
                phase = DeletionPhase.DELETING,
                runTotal = minOf(queue.size, DeletionService.MAX_PER_RUN),
                runSettled = 0,
                result = null,
            )
        }
        job = viewModelScope.launch {
            val result = try {
                service.delete(UUID.randomUUID().toString(), queue) { id, outcome ->
                    _uiState.update { st ->
                        st.copy(
                            runSettled = st.runSettled + 1,
                            rows = st.rows.map {
                                if (it.candidate.id == id) {
                                    it.copy(status = if (outcome == CandidateOutcome.DELETED) RowStatus.DELETED else RowStatus.ALREADY_GONE)
                                } else {
                                    it
                                }
                            },
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Stopped before the first request went out (e.g. while the account was being checked).
                _uiState.update {
                    it.copy(phase = DeletionPhase.DONE, result = RunResult(RunStop.UserStopped, 0, 0, queue.size))
                }
                throw e
            }
            _uiState.update { it.copy(phase = DeletionPhase.DONE, result = result) }
        }
    }

    /**
     * Goes back to the reviewed list to finish what a capped, rate-limited or stopped run left behind,
     * WITHOUT scanning X again (a re-scan burns the same quota the deletions need). The rows already
     * settled stay settled, so only what is genuinely left is offered, and it still needs a confirm.
     */
    fun continueRemaining() {
        val s = _uiState.value
        if (s.phase != DeletionPhase.DONE || s.selectedCount == 0) return
        _uiState.update { it.copy(phase = DeletionPhase.REVIEW, result = null, runTotal = 0, runSettled = 0) }
    }

    /** Takes effect between posts: the request in flight is allowed to finish so the log stays truthful. */
    fun stop() {
        if (_uiState.value.phase == DeletionPhase.DELETING) job?.cancel()
    }

    /** Back to the start; the next find re-reads X, so posts deleted meanwhile are not offered again. */
    fun reset() {
        job?.cancel()
        _uiState.update { DeletionUiState(preset = it.preset, customFrom = it.customFrom, customTo = it.customTo) }
    }

    private fun label(t: Instant): String = LABEL.format(t.atZone(zone))

    private companion object {
        const val FIND_COOLDOWN_MS = 20_000L
        val LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")
    }
}
