package com.clipcut.ui.bossmeter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.domain.bossmeter.BossMeterOutcome
import com.clipcut.domain.bossmeter.BossMeterResult
import com.clipcut.domain.bossmeter.BossMeterService
import com.clipcut.domain.bossmeter.BossMeterStore
import com.clipcut.domain.bossmeter.bossMeterCostUsd
import com.clipcut.domain.bossmeter.hashtagsFrom
import com.clipcut.domain.bossmeter.lookalikeFixes
import com.clipcut.domain.bossmeter.postIdFrom
import com.clipcut.domain.publish.MeterKind
import com.clipcut.domain.publish.PayloadException
import com.clipcut.domain.publish.PublishStatus
import com.clipcut.domain.publish.WebsitePublisher
import com.clipcut.domain.publish.bossPayload
import com.clipcut.domain.api.BOSS_METER_ORDER
import com.clipcut.domain.api.XReadPool
import com.clipcut.domain.api.paidByText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BossMeterUiState(
    val link: String = "",
    val hashtagText: String = "",
    val result: BossMeterResult? = null,
    val running: Boolean = false,
    val progress: String? = null,
    /** The cost is on screen waiting for a yes. Nothing is sent to X until it is confirmed. */
    val confirming: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val hashtags: List<String> get() = hashtagsFrom(hashtagText)
    val postId: String? get() = postIdFrom(link)
    val calls: Int get() = hashtags.size + 1
    val costUsd: Double get() = bossMeterCostUsd(hashtags.size)
    /** Hashtags that probably have a lowercase L where a capital I belongs, with the suggested spelling. */
    val lookalikes: Map<String, String> get() = lookalikeFixes(hashtags)
    val canCount: Boolean get() = !running && postId != null && hashtags.isNotEmpty()
}

@HiltViewModel
class BossMeterViewModel @Inject constructor(
    private val service: BossMeterService,
    private val store: BossMeterStore,
    private val publisher: WebsitePublisher,
    private val pool: XReadPool,
) : ViewModel() {
    /** Which account pays, for the cost box. Balances are re-read (free) when the box opens. */
    val payer: StateFlow<String> = pool.balances.map { pool.payerText(BOSS_METER_ORDER) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, pool.payerText(BOSS_METER_ORDER))

    /** Whether the website has this result. Never affects the result itself. */
    val publishStatus: StateFlow<PublishStatus> = publisher.status.map { it.getValue(MeterKind.BOSS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PublishStatus.Idle)

    fun retryPublish() = publisher.retry(MeterKind.BOSS)

    private val _state = MutableStateFlow(BossMeterUiState(link = store.link, hashtagText = store.hashtags, result = store.lastResult()))
    val state: StateFlow<BossMeterUiState> = _state.asStateFlow()

    fun setLink(v: String) {
        store.link = v
        _state.update { it.copy(link = v, error = null) }
    }

    fun setHashtags(v: String) {
        store.hashtags = v
        _state.update { it.copy(hashtagText = v, error = null) }
    }

    /** Replaces each suspect spelling with the suggestion. Only runs when the user taps Fix. */
    fun fixLookalikes() {
        val fixes = _state.value.lookalikes
        if (fixes.isEmpty()) return
        val text = _state.value.hashtagText.let { t -> fixes.entries.fold(t) { acc, (bad, good) -> acc.replace(bad.trimStart('#'), good.trimStart('#')) } }
        setHashtags(text)
    }

    fun askToCount() {
        viewModelScope.launch { pool.refreshBalances() }
        if (_state.value.canCount) _state.update { it.copy(confirming = true, error = null, message = null) }
    }

    fun cancel() = _state.update { it.copy(confirming = false) }

    fun confirmCount() {
        val s = _state.value
        if (!s.canCount) return
        _state.update { it.copy(confirming = false, running = true, progress = null, error = null, message = null) }
        val callsBefore = pool.calls.value
        viewModelScope.launch {
            val out = service.run(s.link, s.hashtags) { p -> _state.update { it.copy(progress = p) } }
            when (out) {
                is BossMeterOutcome.Done -> {
                    store.saveResult(out.result)
                    _state.update { it.copy(running = false, progress = null, result = out.result, message = "Counted from X: ${s.calls} calls, about $${"%.3f".format(s.costUsd)}. Paid by ${paidByText(callsBefore, pool.calls.value)}.") }
                    publishInBackground(out.result, s.link) // after the result is on screen
                }
                is BossMeterOutcome.Failed -> _state.update { it.copy(running = false, progress = null, error = out.reason) }
            }
        }
    }

    /** Hands the result to the publisher and moves on at once; the publisher does the sending on its own scope. */
    private fun publishInBackground(result: BossMeterResult, link: String) {
        try {
            publisher.publish(bossPayload(result, link))
        } catch (e: PayloadException) {
            publisher.reject(MeterKind.BOSS, e.message ?: "The website cannot take this result.")
        }
    }

    fun showInfo(text: String) = _state.update { it.copy(message = text, error = null) }

    fun showError(reason: String) = _state.update { it.copy(error = reason) }

    fun dismiss() = _state.update { it.copy(error = null, message = null) }
}
