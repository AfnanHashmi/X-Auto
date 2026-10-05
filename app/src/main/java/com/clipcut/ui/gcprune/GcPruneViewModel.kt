package com.clipcut.ui.gcprune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.data.gcprune.WhitelistStore
import com.clipcut.domain.gcprune.ExecSettings
import com.clipcut.domain.gcprune.GcLink
import com.clipcut.domain.gcprune.GcPruneApi
import com.clipcut.domain.gcprune.GcPruneService
import com.clipcut.domain.gcprune.HandleList
import com.clipcut.domain.gcprune.LookupOutcome
import com.clipcut.domain.gcprune.PrunePlanner
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
 * Drives the GC Prune screen: INPUT -> LOOKING_UP -> REVIEW -> (REMOVING -> DONE). Removal can only start from
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
                edit { copy(whitelistMessage = "The whitelist cannot be read (${e.message}). Lookups refuse to run until it is fixed.") }
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
                    val handle = HandleList.handleOf(raw)
                    if (handle == null) {
                        edit { copy(whitelistMessage = "That is not a user id or an @username.") }
                        return@launch
                    }
                    api.lookupHandles(listOf(handle)).value.firstOrNull()?.found?.id ?: run {
                        edit { copy(whitelistMessage = "No account found for @$handle.") }
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

    /** Whitelists a person from the review list: they stay on screen but are protected from now on. */
    fun keep(userId: String) {
        viewModelScope.launch {
            try {
                whitelistStore.add(userId)
                edit {
                    copy(
                        lookup = lookup?.copy(
                            rows = lookup.rows.map {
                                if (it.userId == userId) it.copy(status = PrunePlanner.Status.Protected("on your whitelist")) else it
                            },
                        ),
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

    // ---- lookup ------------------------------------------------------------------------------------------
    fun setLink(v: String) = edit { copy(link = v, message = null) }

    fun setNames(v: String) = edit { copy(namesText = v, message = null) }

    fun lookUp() {
        val s = _state.value
        if (s.phase != GcPhase.INPUT) return
        val id = GcLink.parseGroupId(s.link)
        if (id == null) {
            edit { copy(message = "That is not a group chat link. Paste something like https://x.com/i/chat/g123 or the id starting with g.") }
            return
        }
        if (s.parsed.valid.isEmpty()) {
            edit { copy(message = "Paste at least one username first.") }
            return
        }
        edit {
            copy(
                phase = GcPhase.LOOKING_UP, message = null, lookup = null, report = null,
                allowRemoval = false, typedId = "", confirmOpen = false,
            )
        }
        job = viewModelScope.launch {
            when (val out = service.lookup(id, s.namesText)) {
                is LookupOutcome.Ready ->
                    edit { copy(phase = GcPhase.REVIEW, lookup = out.lookup, selected = out.lookup.defaultSelection()) }
                is LookupOutcome.Aborted -> edit { copy(phase = GcPhase.INPUT, message = out.reason) }
            }
        }
    }

    fun cancelLookup() {
        if (_state.value.phase != GcPhase.LOOKING_UP) return
        job?.cancel()
        edit { copy(phase = GcPhase.INPUT, message = "Lookup cancelled. Nothing was removed.") }
    }

    // ---- review ---------------------------------------------------------------------------------------------
    fun toggle(userId: String) = edit { copy(selected = if (userId in selected) selected - userId else selected + userId) }

    /** Ticks every person who can be removed, or clears all ticks. */
    fun selectAll(on: Boolean) =
        edit { copy(selected = if (on) lookup?.rows?.filter { it.selectable }?.mapNotNull { it.userId }?.toSet().orEmpty() else emptySet()) }

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
        val lookup = s.lookup ?: return
        val cap = s.cap ?: return
        val delayMs = s.delayMs ?: return
        if (!s.canRemove) return
        edit { copy(confirmOpen = false, phase = GcPhase.REMOVING, report = null) }
        job = viewModelScope.launch {
            val report = service.execute(lookup, s.selected, s.typedId, ExecSettings(cap, delayMs)) { r -> edit { copy(report = r) } }
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
