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
import com.clipcut.domain.history.ContestantStore
import com.clipcut.domain.publish.SyncResult
import com.clipcut.domain.publish.WebsiteSync
import com.clipcut.domain.history.RunHistory
import com.clipcut.domain.publish.BossStage
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
    /** Which contestant each hashtag counts for in the weekly ranking. */
    val contestants: Map<String, String> = emptyMap(),
    /** Which post of the round this is, sent to the website as "stage". */
    val stage: BossStage = BossStage.CONTESTANTS,
    /** Marks the finalists' run as the final result. Only with [BossStage.FINALISTS]; switches itself off once published. */
    val final: Boolean = false,
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
    private val history: RunHistory,
    private val contestants: ContestantStore,
    private val sync: WebsiteSync,
) : ViewModel() {
    /** Sends the latest run (stage, final flag, insights) to the website again. No X calls, no cost. */
    fun sendToWebsite() {
        when (val r = sync.sendBoss()) {
            SyncResult.Queued -> Unit // the banner shows Updating / Website updated / Not updated
            is SyncResult.Nothing -> _state.update { it.copy(error = r.reason, message = null) }
        }
    }


    /** Every contestant name used so far, for the link dialog. */
    fun knownContestants(): List<String> = contestants.knownNames()

    fun setContestant(tag: String, name: String) {
        contestants.set(tag, name)
        refreshContestants()
    }

    private fun refreshContestants() = _state.update { s ->
        s.copy(contestants = s.result?.tags.orEmpty().mapNotNull { t -> contestants.contestantFor(t.tag)?.let { t.tag to it } }.toMap())
    }

    /** Which account pays, for the cost box. Balances are re-read (free) when the box opens. */
    val payer: StateFlow<String> = pool.balances.map { pool.payerText(BOSS_METER_ORDER) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, pool.payerText(BOSS_METER_ORDER))

    /** Whether the website has this result. Never affects the result itself. */
    val publishStatus: StateFlow<PublishStatus> = publisher.status.map { it.getValue(MeterKind.BOSS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PublishStatus.Idle)

    fun retryPublish() = publisher.retry(MeterKind.BOSS)

    private val _state = MutableStateFlow(
        BossMeterUiState(
            link = store.link,
            hashtagText = store.hashtags,
            result = store.lastResult(),
            stage = BossStage.from(store.stage) ?: BossStage.defaultFor(hashtagsFrom(store.hashtags).size),
        ),
    )

    /** Set while a "final" run is on its way to the website: the switch goes off when that publish succeeds. */
    private var finalInFlight = false
    val state: StateFlow<BossMeterUiState> = _state.asStateFlow()

    init {
        refreshContestants()
        viewModelScope.launch {
            publisher.status.collect { st ->
                when (st.getValue(MeterKind.BOSS)) {
                    is PublishStatus.Done -> if (finalInFlight) {
                        finalInFlight = false
                        _state.update { it.copy(final = false) }
                    }
                    is PublishStatus.Failed -> Unit // the switch stays on; Retry resends the same final run
                    else -> Unit
                }
            }
        }
    }

    fun setStage(stage: BossStage) {
        store.stage = stage.wire
        _state.update { it.copy(stage = stage, final = it.final && stage == BossStage.FINALISTS) }
    }

    fun setFinal(on: Boolean) = _state.update { it.copy(final = on && it.stage == BossStage.FINALISTS) }

    fun setLink(v: String) {
        store.link = v
        _state.update { it.copy(link = v, error = null) }
    }

    /** When the number of hashtags crosses 2 (finalists) or back, the Round stage follows; a choice made by hand stays otherwise. */
    fun setHashtags(v: String) {
        store.hashtags = v
        val before = _state.value.hashtags.size
        val after = hashtagsFrom(v).size
        _state.update { it.copy(hashtagText = v, error = null) }
        if ((before == 2) != (after == 2)) setStage(BossStage.defaultFor(after))
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
                    history.saveBoss(out.result, s.link, s.stage.wire, s.final) // every run is kept for the Boss Meter timeline and the weekly ranking
                    out.result.tags.forEach { contestants.linkIfNew(it.tag) }
                    _state.update { it.copy(running = false, progress = null, result = out.result, message = "Counted from X: ${s.calls} calls, about $${"%.3f".format(s.costUsd)}. Paid by ${paidByText(callsBefore, pool.calls.value)}.") }
                    refreshContestants()
                    publishInBackground(out.result, s.link, s.stage, s.final) // after the result is on screen
                }
                is BossMeterOutcome.Failed -> _state.update { it.copy(running = false, progress = null, error = out.reason) }
            }
        }
    }

    /** Hands the result to the publisher and moves on at once; the publisher does the sending on its own scope. */
    private fun publishInBackground(result: BossMeterResult, link: String, stage: BossStage, final: Boolean) {
        try {
            val payload = bossPayload(result, link, stage, final, withInsights = true)
            finalInFlight = final
            publisher.publish(payload)
        } catch (e: PayloadException) {
            publisher.reject(MeterKind.BOSS, e.message ?: "The website cannot take this result.")
        }
    }

    fun showInfo(text: String) = _state.update { it.copy(message = text, error = null) }

    fun showError(reason: String) = _state.update { it.copy(error = reason) }

    fun dismiss() = _state.update { it.copy(error = null, message = null) }
}
