package com.clipcut.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.data.radar.RadarCache
import com.clipcut.domain.radar.LastJob
import com.clipcut.domain.radar.Outcome
import com.clipcut.domain.radar.RadarReport
import com.clipcut.domain.radar.RadarService
import com.clipcut.domain.radar.RadarSummary
import com.clipcut.domain.radar.RunKind
import com.clipcut.domain.radar.RunPreview
import com.clipcut.ui.caption.CaptionSeed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

enum class RadarTab(val label: String) { REPORT("Report"), COST("Cost"), SETTINGS("Settings") }

/** A run waiting for you to confirm, with what it would cost. Nothing is spent until [TrendRadarViewModel.confirmRun]. */
data class PendingRun(val type: RunKind, val posts: Int?, val focus: String?, val preview: RunPreview)

data class RadarUiState(
    val tab: RadarTab = RadarTab.REPORT,
    val report: RadarReport? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val pending: PendingRun? = null,
    /** True from the moment a run is queued until the Worker reports it finished. */
    val awaitingRun: Boolean = false,
    val awaitingSince: Long = 0,
    val acting: Boolean = false,
    val configured: Boolean = true,
) {
    /** Poll faster while a run is on its way. */
    val busy: Boolean get() = awaitingRun || (report?.job?.queued ?: 0) > 0 || report?.job?.running == true
}

@HiltViewModel
class TrendRadarViewModel @Inject constructor(
    private val service: RadarService,
    private val cache: RadarCache,
    private val seed: CaptionSeed,
) : ViewModel() {
    private val _state = MutableStateFlow(RadarUiState(configured = service.isConfigured))
    val state: StateFlow<RadarUiState> = _state.asStateFlow()

    fun setTab(tab: RadarTab) = _state.update { it.copy(tab = tab) }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    /** Reading is free: only runs cost money, and only when you confirm them. */
    fun refresh() {
        if (_state.value.loading || !service.isConfigured) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val r = service.report()) {
                is Outcome.Ok -> {
                    cache.save(RadarSummary.of(r.value))
                    _state.update { s -> finishWaiting(s, r.value).copy(report = r.value, loading = false) }
                }
                is Outcome.Failed -> _state.update { it.copy(loading = false, error = r.reason) }
            }
        }
    }

    /** When the run you queued has finished (or been refused at the last moment), say what happened. */
    private fun finishWaiting(s: RadarUiState, r: RadarReport): RadarUiState {
        if (!s.awaitingRun) return s
        val last: LastJob? = r.job.last
        val done = r.job.queued == 0 && !r.job.running && last != null && last.atMs >= s.awaitingSince
        if (!done) return s
        val cost = r.runs.firstOrNull { it.trigger == "manual" }?.costUsd
        return s.copy(
            awaitingRun = false,
            message = if (last!!.ok) "Done" + (cost?.let { " (cost $" + "%.3f".format(it) + ")" } ?: "") + "." else null,
            error = if (last.ok) null else (last.reason ?: "The run did not finish."),
            tab = RadarTab.REPORT,
        )
    }

    // ---- day session ------------------------------------------------------------------------------
    fun setSession(on: Boolean, budgetText: String) {
        val budget = budgetText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (on && budgetText.isNotBlank() && budget == null) {
            _state.update { it.copy(error = "The session limit must be an amount like 4 or 4.50.") }
            return
        }
        act { service.setSession(on, budget) }
    }

    // ---- runs -------------------------------------------------------------------------------------------
    /** Step one of a paid run: ask the Worker what it would cost. Nothing is spent here. */
    fun requestRun(type: RunKind, posts: Int? = null, focus: String? = null) {
        if (_state.value.busy) return
        viewModelScope.launch {
            when (val p = service.preview(type, posts)) {
                is Outcome.Ok -> _state.update { it.copy(pending = PendingRun(type, posts, focus, p.value), error = null, message = null) }
                is Outcome.Failed -> _state.update { it.copy(error = p.reason) }
            }
        }
    }

    fun cancelPending() = _state.update { it.copy(pending = null) }

    /** Step two, after you confirm: the Worker queues the run and starts it within about a minute. */
    fun confirmRun() {
        val p = _state.value.pending ?: return
        _state.update { it.copy(pending = null, acting = true, error = null, message = null) }
        viewModelScope.launch {
            when (val r = service.run(p.type, p.posts, p.focus)) {
                is Outcome.Ok -> {
                    _state.update { it.copy(acting = false, awaitingRun = true, awaitingSince = it.report?.serverTimeMs ?: System.currentTimeMillis(), message = "Queued. It starts within about a minute.") }
                    refresh()
                }
                is Outcome.Failed -> _state.update { it.copy(acting = false, error = r.reason) }
            }
        }
    }

    /** Free: replays a saved real answer so you can see what a report looks like. Spends nothing. */
    fun sample(type: RunKind) = act { service.sample(type) }

    // ---- small actions ------------------------------------------------------------------------------------
    fun excludeHandle(handle: String) = act("@${handle.removePrefix("@")} will be left out of future runs.") { service.exclude(handle) }

    fun saveSettings(patch: JSONObject) = act("Settings saved.") { service.saveConfig(patch) }

    /** Hands an idea to the existing Caption Generator (OpenAI drafts there, only if you tap Enhance). */
    fun draftIdea(idea: String) = seed.put(idea)

    private fun act(message: String? = null, block: suspend () -> Outcome<*>) {
        if (_state.value.acting) return
        _state.update { it.copy(acting = true, error = null, message = null) }
        viewModelScope.launch {
            when (val r = block()) {
                is Outcome.Ok -> {
                    _state.update { it.copy(acting = false, message = message) }
                    refresh()
                }
                is Outcome.Failed -> _state.update { it.copy(acting = false, error = r.reason) }
            }
        }
    }
}
