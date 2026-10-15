package com.clipcut.ui.taglines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.domain.taglines.CountOutcome
import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.MAX_TAGLINES
import com.clipcut.domain.taglines.TaglineStore
import com.clipcut.domain.taglines.TaglinesService
import com.clipcut.domain.taglines.cleanTagline
import com.clipcut.domain.taglines.estimatedCostUsd
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaglinesUiState(
    val taglines: List<String> = emptyList(),
    val report: CountReport? = null,
    val running: Boolean = false,
    /** Set while the cost is on screen waiting for a yes. Nothing is sent to X until it is confirmed. */
    val confirming: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val estimatedCostUsd: Double get() = estimatedCostUsd(taglines.size)
}

@HiltViewModel
class TaglinesViewModel @Inject constructor(
    private val service: TaglinesService,
    private val store: TaglineStore,
) : ViewModel() {
    private val _state = MutableStateFlow(TaglinesUiState(taglines = store.taglines(), report = store.lastReport()))
    val state: StateFlow<TaglinesUiState> = _state.asStateFlow()

    fun add(raw: String) {
        val t = cleanTagline(raw) ?: return
        val now = _state.value.taglines
        when {
            now.any { it.equals(t, ignoreCase = true) } -> _state.update { it.copy(error = "That tagline is already in the list.", message = null) }
            now.size >= MAX_TAGLINES -> _state.update { it.copy(error = "At most $MAX_TAGLINES taglines, to keep the cost small.", message = null) }
            else -> save(now + t)
        }
    }

    fun remove(tagline: String) = save(_state.value.taglines - tagline)

    private fun save(list: List<String>) {
        store.saveTaglines(list)
        _state.update { it.copy(taglines = list, error = null, message = null) }
    }

    /** Step one: show what it will cost. Nothing is sent yet. */
    fun askToCount() {
        if (_state.value.taglines.isEmpty() || _state.value.running) return
        _state.update { it.copy(confirming = true, error = null, message = null) }
    }

    fun cancel() = _state.update { it.copy(confirming = false) }

    /** Step two: the user said yes. */
    fun confirmCount() {
        val s = _state.value
        if (s.running || s.taglines.isEmpty()) return
        _state.update { it.copy(confirming = false, running = true, error = null, message = null) }
        viewModelScope.launch {
            when (val out = service.count(s.taglines)) {
                is CountOutcome.Done -> {
                    store.saveReport(out.report)
                    _state.update { it.copy(running = false, report = out.report, message = "Counted from X. About $${"%.3f".format(estimatedCostUsd(out.report.counts.size))} billed by X.") }
                }
                is CountOutcome.Failed -> _state.update { it.copy(running = false, error = out.reason) }
            }
        }
    }

    fun dismiss() = _state.update { it.copy(error = null, message = null) }
}
