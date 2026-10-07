package com.clipcut.ui.unfollows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.data.db.UnfollowDao
import com.clipcut.domain.unfollows.ActionOutcome
import com.clipcut.domain.unfollows.GcStatus
import com.clipcut.domain.unfollows.RefreshOutcome
import com.clipcut.domain.unfollows.TrackerStatus
import com.clipcut.domain.unfollows.UnfollowRow
import com.clipcut.domain.unfollows.UnfollowsService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

enum class UnfollowFilter(val label: String) {
    ALL("All"),
    KICKED("Kicked"),
    WOULD_KICK("Would kick"),
    NOT_IN_GC("Not in group"),
}

data class UnfollowsUiState(
    val rows: List<UnfollowRow> = emptyList(),
    val status: TrackerStatus? = null,
    val filter: UnfollowFilter = UnfollowFilter.ALL,
    val refreshing: Boolean = false,
    /** True while a mode change, resume or "never kick" is being sent. */
    val acting: Boolean = false,
    /** Set when the last refresh or action failed; the list below is then whatever was stored before. */
    val error: String? = null,
    val warning: String? = null,
    val lastResult: String? = null,
) {
    private fun localDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    fun unfollowedToday(today: LocalDate = LocalDate.now()): Int = rows.count { localDay(it.event.receivedAtEpochMs) == today }

    fun kickedToday(today: LocalDate = LocalDate.now()): Int =
        rows.count { GcStatus.isKicked(it.event.gcStatus) && localDay(it.event.gcUpdatedAtEpochMs ?: it.event.receivedAtEpochMs) == today }

    val kickedRows: List<UnfollowRow> get() = rows.filter { GcStatus.isKicked(it.event.gcStatus) }

    val visibleRows: List<UnfollowRow>
        get() = when (filter) {
            UnfollowFilter.ALL -> rows
            UnfollowFilter.KICKED -> rows.filter { GcStatus.isKicked(it.event.gcStatus) }
            UnfollowFilter.WOULD_KICK -> rows.filter { it.event.gcStatus == GcStatus.WOULD_KICK }
            UnfollowFilter.NOT_IN_GC -> rows.filter { it.event.gcStatus == "not_in_gc" }
        }

    /** Events received this UTC month, at what X charges per delivered event. A figure to glance at, not a bill. */
    fun monthCostUsd(nowMs: Long = System.currentTimeMillis()): Double {
        val month = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).let { it.year * 12 + it.monthValue }
        val n = rows.count { Instant.ofEpochMilli(it.event.receivedAtEpochMs).atZone(ZoneOffset.UTC).let { z -> z.year * 12 + z.monthValue } == month }
        return n * UnfollowsService.EVENT_COST_USD
    }
}

@HiltViewModel
class UnfollowsViewModel @Inject constructor(
    private val service: UnfollowsService,
    dao: UnfollowDao,
) : ViewModel() {
    private val _state = MutableStateFlow(UnfollowsUiState())
    val state: StateFlow<UnfollowsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            dao.observeAll().collect { list -> _state.update { it.copy(rows = UnfollowsService.rows(list)) } }
        }
        refresh()
    }

    fun setFilter(f: UnfollowFilter) = _state.update { it.copy(filter = f) }

    /** Reading the tracker is free; X bills when an event is delivered to it, not when this app asks. */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            when (val out = service.refresh()) {
                is RefreshOutcome.Done -> _state.update {
                    it.copy(
                        refreshing = false,
                        status = out.status,
                        warning = out.warning,
                        lastResult = when {
                            out.newEvents > 0 -> "${out.newEvents} new."
                            out.changed > 0 -> "${out.changed} updated."
                            else -> "Up to date."
                        },
                    )
                }
                is RefreshOutcome.Failed -> _state.update { it.copy(refreshing = false, error = out.reason) }
            }
        }
    }

    fun setMode(mode: String) = act { service.setMode(mode) }

    fun resume() = act { service.resume() }

    /** "Never kick": whitelists the person, so the automation leaves them alone from now on. */
    fun protect(userId: String) = act { service.protect(userId) }

    private fun act(block: suspend () -> ActionOutcome) {
        if (_state.value.acting) return
        _state.update { it.copy(acting = true, error = null) }
        viewModelScope.launch {
            when (val out = block()) {
                ActionOutcome.Done -> {
                    _state.update { it.copy(acting = false) }
                    refresh()
                }
                is ActionOutcome.Failed -> _state.update { it.copy(acting = false, error = out.reason) }
            }
        }
    }
}
