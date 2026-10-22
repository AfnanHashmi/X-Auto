package com.clipcut.ui.scheduler

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.domain.scheduler.ALL_DAYS
import com.clipcut.domain.scheduler.NightRule
import com.clipcut.domain.scheduler.ScheduleEdit
import com.clipcut.domain.scheduler.SchedStatus
import com.clipcut.domain.scheduler.SchedulerApi
import com.clipcut.domain.scheduler.SchedulerException
import com.clipcut.domain.scheduler.nightRulesProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The schedule being edited, before it is sent to the laptop. */
data class ScheduleDraft(val morningEnabled: Boolean, val morningTime: String, val nightEnabled: Boolean, val nightRules: List<NightRule>)

data class SchedulerUi(
    val status: SchedStatus? = null,
    /** True until the first reply (or failure) arrives. */
    val loading: Boolean = true,
    val busy: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val draft: ScheduleDraft? = null,
)

/** How often the status is re-read: quickly while a run is going, slowly otherwise. The laptop allows 30 requests a minute. */
private const val POLL_ACTIVE_MS = 4_000L
private const val POLL_IDLE_MS = 20_000L

@HiltViewModel
class SchedulerViewModel @Inject constructor(private val api: SchedulerApi) : ViewModel() {
    private val _state = MutableStateFlow(SchedulerUi())
    val state: StateFlow<SchedulerUi> = _state.asStateFlow()

    val isConfigured: Boolean get() = api.isConfigured

    /** Follows the laptop while the screen is showing. Cancelled with the screen. */
    suspend fun watch() {
        while (true) {
            refreshNow()
            val active = _state.value.status?.let { it.activeRun != null || it.state != "idle" && it.state != "recording" } == true
            delay(if (active) POLL_ACTIVE_MS else POLL_IDLE_MS)
        }
    }

    private suspend fun refreshNow() {
        if (!api.isConfigured) {
            _state.update { it.copy(loading = false) }
            return
        }
        try {
            val s = api.status()
            _state.update { it.copy(status = s, loading = false, error = if (it.error?.startsWith("Could not reach") == true) null else it.error) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SchedulerException) {
            _state.update { it.copy(loading = false, error = e.message) }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    fun dismiss() = _state.update { it.copy(error = null, notice = null) }

    private fun act(busy: String, done: String, closeEditor: Boolean = false, call: suspend () -> String?) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = busy, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val extra = call()
                refreshNow()
                _state.update { it.copy(busy = null, notice = extra ?: done, draft = if (closeEditor) null else it.draft) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SchedulerException) {
                _state.update { it.copy(busy = null, error = e.message) }
            }
        }
    }

    /** Starts recording now, the same job as the morning schedule. */
    fun startMorning(requireStreaming: Boolean) = act("Starting recording…", "Recording is starting.") { api.triggerMorning(requireStreaming); null }

    /** Stops, uploads, and (when [shutdown]) shuts the laptop down after a 60 second countdown. */
    fun startNight(shutdown: Boolean) = act(
        "Starting the night job…",
        if (shutdown) "Night job started. The laptop shuts down 60 seconds after a verified upload." else "Night job started. The laptop stays on.",
    ) { api.triggerNight(shutdown); null }

    fun cancelRun() = act("Cancelling…", "Cancelled.") { api.cancel() }

    // ---- Schedule editing ---------------------------------------------------------------------------------------------

    fun openEditor() {
        val s = _state.value.status ?: return
        _state.update {
            it.copy(
                draft = ScheduleDraft(s.morning.enabled, s.morning.time.ifBlank { "10:00" }, s.night.enabled, s.night.rules),
                error = null, notice = null,
            )
        }
    }

    fun closeEditor() = _state.update { if (it.busy != null) it else it.copy(draft = null) }

    private fun edit(f: (ScheduleDraft) -> ScheduleDraft) = _state.update { st -> st.draft?.let { st.copy(draft = f(it), error = null) } ?: st }

    fun setMorningEnabled(on: Boolean) = edit { it.copy(morningEnabled = on) }

    fun setMorningTime(time: String) = edit { it.copy(morningTime = time) }

    fun setNightEnabled(on: Boolean) = edit { it.copy(nightEnabled = on) }

    private fun editRule(index: Int, f: (NightRule) -> NightRule) = edit { d ->
        d.copy(nightRules = d.nightRules.mapIndexed { i, r -> if (i == index) f(r) else r })
    }

    /** Switches one day of a rule on or off. A day can only be in one rule, so switching it on takes it out of the others. */
    fun toggleDay(index: Int, day: String) = edit { d ->
        val rule = d.nightRules.getOrNull(index) ?: return@edit d
        val turnOn = day !in rule.days
        d.copy(
            nightRules = d.nightRules.mapIndexed { i, r ->
                when {
                    i == index -> r.copy(days = ALL_DAYS.filter { x -> if (x == day) turnOn else x in r.days })
                    turnOn -> r.copy(days = r.days - day)
                    else -> r
                }
            },
        )
    }

    fun addNightTime(index: Int, time: String) = editRule(index) { r -> if (time in r.times) r else r.copy(times = (r.times + time).sorted()) }

    fun removeNightTime(index: Int, time: String) = editRule(index) { r -> r.copy(times = r.times - time) }

    fun addRule() = edit { d ->
        val used = d.nightRules.flatMap { it.days }.toSet()
        d.copy(nightRules = d.nightRules + NightRule(days = ALL_DAYS.filter { it !in used }.take(1), times = listOf("01:00")))
    }

    fun removeRule(index: Int) = edit { d -> d.copy(nightRules = d.nightRules.filterIndexed { i, _ -> i != index }) }

    /** Why the draft cannot be saved, or null. A switched-off night job needs no valid rules. */
    fun draftProblem(d: ScheduleDraft): String? = if (d.nightEnabled) nightRulesProblem(d.nightRules) else null

    fun saveSchedule() {
        val d = _state.value.draft ?: return
        draftProblem(d)?.let { p -> _state.update { it.copy(error = p) }; return }
        val s = _state.value.status
        // Send only what the user changed, so the laptop's own copy of the rest is never overwritten.
        val edit = ScheduleEdit(
            morningEnabled = d.morningEnabled.takeIf { s == null || it != s.morning.enabled },
            morningTime = d.morningTime.takeIf { s == null || it != s.morning.time },
            nightEnabled = d.nightEnabled.takeIf { s == null || it != s.night.enabled },
            nightRules = d.nightRules.takeIf { d.nightEnabled && (s == null || it != s.night.rules) },
        )
        // The editor stays open if the laptop refuses, so nothing typed is lost.
        act("Saving the schedule…", "Schedule saved on the laptop. It applies at once.", closeEditor = true) { api.putSchedule(edit); null }
    }
}
