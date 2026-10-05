package com.clipcut.ui.gcprune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.data.gcprune.WhitelistStore
import com.clipcut.domain.gcprune.EstimateOutcome
import com.clipcut.domain.gcprune.ExecSettings
import com.clipcut.domain.gcprune.GcLink
import com.clipcut.domain.gcprune.GcPruneApi
import com.clipcut.domain.gcprune.GcPruneService
import com.clipcut.domain.gcprune.ScanOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the GC Prune screen: INPUT -> SCANNING -> REVIEW -> (REMOVING -> DONE). Removal can only start from
 * REVIEW, with removal switched on and the group id typed exactly; the service enforces the same again.
 */
@HiltViewModel
class GcPruneViewModel @Inject constructor(
    private val service: GcPruneService,
    private val whitelistStore: WhitelistStore,
    private val api: GcPruneApi,
) : ViewModel() {
    private val _state = MutableStateFlow(GcPruneUiState())
    val state: StateFlow<GcPruneUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        reloadWhitelist()
    }

    private fun edit(change: GcPruneUiState.() -> GcPruneUiState) = _state.update { it.change() }

    // ---- whitelist ---------------------------------------------------------------------------------------
    private fun reloadWhitelist() {
        viewModelScope.launch {
            try {
                val ids = whitelistStore.load()
                edit { copy(whitelist = ids, whitelistMessage = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                edit { copy(whitelistMessage = "The whitelist cannot be read (${e.message}). Scans refuse to run until it is fixed.") }
            }
        }
    }

    fun setWhitelistInput(v: String) = edit { copy(whitelistInput = v, whitelistMessage = null) }

    /** Adds a numeric user id, or looks an @handle up to find its id. */
    fun addToWhitelist() {
        val raw = _state.value.whitelistInput.trim()
        if (raw.isEmpty()) return
        viewModelScope.launch {
            try {
                val id = if (raw.all { it.isDigit() } && raw.length <= 19) {
                    raw
                } else {
                    api.lookupHandle(raw).value?.id ?: run {
                        edit { copy(whitelistMessage = "No account found for $raw.") }
                        return@launch
                    }
                }
                whitelistStore.add(id)
                edit { copy(whitelistInput = "", whitelistMessage = "Added $id.") }
                reloadWhitelist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                edit { copy(whitelistMessage = "Could not add them: ${e.message}") }
            }
        }
    }

    fun removeFromWhitelist(id: String) {
        viewModelScope.launch {
            try {
                whitelistStore.remove(id)
                reloadWhitelist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                edit { copy(whitelistMessage = "Could not update the whitelist: ${e.message}") }
            }
        }
    }

    /** Adds a person from the review list to the whitelist and takes them off the list for good. */
    fun keep(userId: String) {
        viewModelScope.launch {
            try {
                whitelistStore.add(userId)
                edit {
                    copy(
                        scan = scan?.copy(candidates = scan.candidates.filter { it.userId != userId }),
                        selected = selected - userId,
                    )
                }
                reloadWhitelist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                edit { copy(whitelistMessage = "Could not protect them: ${e.message}") }
            }
        }
    }

    // ---- scan --------------------------------------------------------------------------------------------
    fun setLink(v: String) = edit { copy(link = v, message = null) }

    /** First step: work out what the scan will cost and ask. Nothing is read until the user approves. */
    fun scan() {
        if (_state.value.phase != GcPhase.INPUT) return
        val id = GcLink.parseGroupId(_state.value.link)
        if (id == null) {
            edit { copy(message = "That is not a group chat link. Paste something like https://x.com/i/chat/g123 or the id starting with g.") }
            return
        }
        edit {
            copy(
                phase = GcPhase.SCANNING, message = null, followersFetched = 0, scan = null, report = null,
                allowRemoval = false, typedId = "", confirmOpen = false, estimate = null, pendingGroupId = null,
            )
        }
        job = viewModelScope.launch {
            when (val e = service.estimate()) {
                is EstimateOutcome.Ready -> edit { copy(phase = GcPhase.CONFIRM_COST, estimate = e.estimate, pendingGroupId = id) }
                is EstimateOutcome.Failed -> edit { copy(phase = GcPhase.INPUT, message = e.reason) }
            }
        }
    }

    fun declineScan() {
        if (_state.value.phase != GcPhase.CONFIRM_COST) return
        edit { copy(phase = GcPhase.INPUT, estimate = null, pendingGroupId = null, message = "Scan not started. Nothing was read or removed.") }
    }

    /** Second step, only after the cost was approved: the real scan. */
    fun confirmScan() {
        val s = _state.value
        val id = s.pendingGroupId ?: return
        if (s.phase != GcPhase.CONFIRM_COST) return
        edit { copy(phase = GcPhase.SCANNING, followersFetched = 0) }
        job = viewModelScope.launch {
            when (val out = service.scan(id) { n -> edit { copy(followersFetched = n) } }) {
                is ScanOutcome.Ready ->
                    edit { copy(phase = GcPhase.REVIEW, scan = out.scan, selected = out.scan.candidates.map { it.userId }.toSet(), estimate = null) }
                is ScanOutcome.Aborted -> edit { copy(phase = GcPhase.INPUT, message = out.reason, estimate = null) }
            }
        }
    }

    fun cancelScan() {
        if (_state.value.phase != GcPhase.SCANNING) return
        job?.cancel()
        edit { copy(phase = GcPhase.INPUT, message = "Scan cancelled. Nothing was removed.") }
    }

    // ---- review ---------------------------------------------------------------------------------------------
    fun toggle(userId: String) = edit { copy(selected = if (userId in selected) selected - userId else selected + userId) }

    fun selectAll(on: Boolean) = edit { copy(selected = if (on) scan?.candidates?.map { it.userId }?.toSet().orEmpty() else emptySet()) }

    // ---- removal ----------------------------------------------------------------------------------------------
    fun setAllowRemoval(on: Boolean) = edit { copy(allowRemoval = on, typedId = if (on) typedId else "") }

    fun setCap(v: String) = edit { copy(capText = v.filter { it.isDigit() }.take(3)) }

    fun setDelay(v: String) = edit { copy(delayText = v.filter { it.isDigit() }.take(3)) }

    fun setTyped(v: String) = edit { copy(typedId = v) }

    fun requestRemove() {
        if (_state.value.canRemove) edit { copy(confirmOpen = true) }
    }

    fun dismissConfirm() = edit { copy(confirmOpen = false) }

    fun confirmRemove() {
        val s = _state.value
        val scan = s.scan ?: return
        val cap = s.cap ?: return
        val delayMs = s.delayMs ?: return
        if (!s.canRemove) return
        edit { copy(confirmOpen = false, phase = GcPhase.REMOVING, report = null) }
        job = viewModelScope.launch {
            val report = service.execute(scan, s.selected, s.typedId, ExecSettings(cap, delayMs)) { r -> edit { copy(report = r) } }
            edit { copy(phase = GcPhase.DONE, report = report, allowRemoval = false, typedId = "") }
        }
    }

    /** Takes effect between people: the request in flight is allowed to finish so the record stays true. */
    fun stop() {
        if (_state.value.phase == GcPhase.REMOVING) job?.cancel()
    }

    fun reset() {
        job?.cancel()
        edit { GcPruneUiState(link = link, whitelist = whitelist, whitelistMessage = whitelistMessage) }
    }
}
