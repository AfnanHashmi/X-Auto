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
import com.clipcut.domain.publish.MeterKind
import com.clipcut.domain.publish.PayloadException
import com.clipcut.domain.publish.PublishStatus
import com.clipcut.domain.publish.WebsitePublisher
import com.clipcut.domain.publish.trendPayload
import com.clipcut.domain.api.TAGLINE_ORDER
import com.clipcut.domain.api.XReadPool
import com.clipcut.domain.api.paidByText
import com.clipcut.domain.history.ContestantStore
import com.clipcut.domain.history.HistorySummary
import com.clipcut.domain.history.RunHistory
import com.clipcut.domain.taglines.DeepLook
import com.clipcut.domain.taglines.DeepLookSize
import com.clipcut.domain.taglines.DeepLookService
import com.clipcut.domain.taglines.DeepLookStore
import com.clipcut.domain.taglines.ReadoutRecord
import com.clipcut.domain.taglines.regulars
import com.clipcut.domain.taglines.TaglineAnalysis
import com.clipcut.domain.taglines.TaglineAnalyst
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /** The AI read-out of [report]; cleared whenever a new count arrives. */
    val analysis: TaglineAnalysis? = null,
    val analysing: Boolean = false,
    /** The AI read-out's cost box is open. */
    val confirmingAnalysis: Boolean = false,
    val analysisHighQuality: Boolean = false,
    /** Deep looks for this count, by tagline. Paid for, so kept with the count. */
    val deepLooks: Map<String, DeepLook> = emptyMap(),
    /** The tagline whose Deep look is running, if any. */
    val deepLooking: String? = null,
    /** The tagline whose Deep look cost box is open. */
    val confirmDeepLook: String? = null,
    /** The Deep look size chosen in the cost box. */
    val lookSize: DeepLookSize = DeepLookSize.STANDARD,
    /** How many Deep looks are kept per tagline (all counts), and the accounts that keep coming up as top voices. */
    val lookCounts: Map<String, Int> = emptyMap(),
    val regulars: Map<String, List<Pair<String, Int>>> = emptyMap(),
    /** Share as a long post (X Premium) with the full read-out and Deep looks; off keeps the post text to 280 characters. */
    val longPosts: Boolean = true,
    /** Which contestant each tagline counts for in the weekly ranking. */
    val contestants: Map<String, String> = emptyMap(),
    /** What the history holds from the last 7 days, for the "kept for the weekly ranking" line. */
    val collected: HistorySummary? = null,
) {
    val estimatedCostUsd: Double get() = estimatedCostUsd(taglines.size)
}

@HiltViewModel
class TaglinesViewModel @Inject constructor(
    private val service: TaglinesService,
    private val store: TaglineStore,
    private val publisher: WebsitePublisher,
    private val pool: XReadPool,
    private val analyst: TaglineAnalyst,
    private val deepLook: DeepLookService,
    private val deepLookStore: DeepLookStore,
    private val history: RunHistory,
    private val contestants: ContestantStore,
) : ViewModel() {
    /** Which account pays, for the cost box. Balances are re-read (free) when the box opens. */
    val payer: StateFlow<String> = pool.balances.map { pool.payerText(TAGLINE_ORDER) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, pool.payerText(TAGLINE_ORDER))

    /** Whether the website has this result. Never affects the result itself. */
    val publishStatus: StateFlow<PublishStatus> = publisher.status.map { it.getValue(MeterKind.TREND) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PublishStatus.Idle)

    fun retryPublish() = publisher.retry(MeterKind.TREND)

    private val _state = MutableStateFlow(
        store.lastReport().let { r ->
            TaglinesUiState(
                taglines = store.taglines(),
                report = r,
                deepLooks = r?.let { deepLookStore.looksFor(it.fetchedAtMs) }.orEmpty(),
                analysis = r?.let { deepLookStore.readoutFor(it.fetchedAtMs) },
                longPosts = store.longPosts,
            )
        },
    )
    val state: StateFlow<TaglinesUiState> = _state.asStateFlow()

    init {
        refreshHistory()
        _state.value.taglines.forEach { contestants.linkIfNew(it) }
        refreshContestants()
    }

    /** The links for the taglines on screen, and what has been kept this week. */
    private fun refreshContestants() {
        val weekAgo = System.currentTimeMillis() - 7 * 24 * 3_600_000L
        _state.update { s ->
            s.copy(
                contestants = (s.taglines + s.report?.counts?.map { it.tagline }.orEmpty()).distinct().mapNotNull { t -> contestants.contestantFor(t)?.let { t to it } }.toMap(),
                collected = history.summary(weekAgo),
            )
        }
    }

    fun setContestant(tagline: String, name: String) {
        contestants.set(tagline, name)
        refreshContestants()
    }

    /** Every contestant name used so far, for the link dialog. */
    fun knownContestants(): List<String> = contestants.knownNames()

    /** Positions typed when sharing are kept with the count, as facts for the ranking. */
    fun recordTrending(reportAtMs: Long, positions: Map<String, Int>) {
        positions.forEach { (t, p) -> history.recordTrending(reportAtMs, t, p) }
        if (positions.isNotEmpty()) refreshContestants()
    }

    /** Counts of kept looks and the steady top voices per tagline, from the whole history. */
    private fun refreshHistory() {
        val all = deepLookStore.allLooks()
        val byTagline = all.groupBy { it.tagline }
        _state.update { s -> s.copy(lookCounts = byTagline.mapValues { it.value.size }, regulars = byTagline.mapValues { regulars(it.value) }) }
    }

    fun setLongPosts(on: Boolean) {
        store.longPosts = on
        _state.update { it.copy(longPosts = on) }
    }

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
        list.forEach { contestants.linkIfNew(it) }
        _state.update { it.copy(taglines = list, error = null, message = null) }
        refreshContestants()
    }

    /** Step one: show what it will cost. Nothing is sent yet. */
    fun askToCount() {
        viewModelScope.launch { pool.refreshBalances() }
        if (_state.value.taglines.isEmpty() || _state.value.running) return
        _state.update { it.copy(confirming = true, error = null, message = null) }
    }

    fun cancel() = _state.update { it.copy(confirming = false) }

    /** Step two: the user said yes. */
    fun confirmCount() {
        val s = _state.value
        if (s.running || s.taglines.isEmpty()) return
        _state.update { it.copy(confirming = false, running = true, error = null, message = null) }
        val callsBefore = pool.calls.value
        viewModelScope.launch {
            when (val out = service.count(s.taglines)) {
                is CountOutcome.Done -> {
                    store.saveReport(out.report)
                    history.saveTagline(out.report) // every count is kept for the timeline and the weekly ranking
                    _state.update { it.copy(running = false, report = out.report, analysis = null, deepLooks = emptyMap(), message = "Counted from X. About $${"%.3f".format(estimatedCostUsd(out.report.counts.size))} billed by X. Paid by ${paidByText(callsBefore, pool.calls.value)}.") }
                    refreshContestants()
                    publishTrend() // after the result is on screen
                }
                is CountOutcome.Failed -> _state.update { it.copy(running = false, error = out.reason) }
            }
        }
    }

    /** Who would write the read-out ("xAI · grok-4.3"), or null with no LLM key. */
    fun analysisLabel(): String? = analyst.plannedLabel(_state.value.analysisHighQuality)

    fun analysisHasHigherTier(): Boolean = analyst.hasHigherTier

    fun analysisEstimateUsd(): Double? = analyst.estimateUsd(_state.value.analysisHighQuality)

    fun askToAnalyse() {
        if (_state.value.report == null || _state.value.analysing) return
        if (!analyst.isConfigured) {
            _state.update { it.copy(error = "No LLM key: add XAI_API_KEY (or OPENAI_API_KEY) to local.properties.", message = null) }
            return
        }
        _state.update { it.copy(confirmingAnalysis = true, error = null, message = null) }
    }

    fun setAnalysisHighQuality(on: Boolean) = _state.update { it.copy(analysisHighQuality = on) }

    fun cancelAnalysis() = _state.update { it.copy(confirmingAnalysis = false) }

    /** Sends only the figures already on screen to the LLM. No X calls. */
    fun confirmAnalysis() {
        val s = _state.value
        val report = s.report ?: return
        if (s.analysing) return
        _state.update { it.copy(confirmingAnalysis = false, analysing = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                // Built on this count's Deep looks, every earlier look (steady voices) and the last read-out of an earlier count.
                val a = analyst.analyze(
                    report, ZoneId.systemDefault(), s.analysisHighQuality,
                    looks = s.deepLooks,
                    history = deepLookStore.allLooks(),
                    previous = deepLookStore.previousReadout(report.fetchedAtMs),
                )
                deepLookStore.addReadout(ReadoutRecord(report.fetchedAtMs, report.counts.associate { it.tagline to it.all.total }, a))
                if (_state.value.report?.fetchedAtMs == report.fetchedAtMs) {
                    _state.update { it.copy(analysing = false, analysis = a) }
                    publishTrend() // the same run again, now with the read-out
                } else {
                    _state.update { it.copy(analysing = false) } // kept on the phone with its own (older) count
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(analysing = false, error = "AI read-out failed: ${e.message}") }
            }
        }
    }

    /** Spent today on Deep looks, and the cap, for the cost box. */
    fun deepLookSpentToday(): Double = deepLook.spentToday()

    fun askDeepLook(tagline: String) {
        val s = _state.value
        if (s.report == null || s.deepLooking != null) return
        // The smallest size must fit today's limit; a bigger one is checked when chosen.
        deepLook.blockedReason(DeepLookSize.STANDARD)?.let { r -> _state.update { it.copy(error = r, message = null) }; return }
        _state.update { it.copy(confirmDeepLook = tagline, error = null, message = null) }
    }

    fun setLookSize(size: DeepLookSize) = _state.update { it.copy(lookSize = size) }

    /** Why the chosen size cannot run today, or null. */
    fun lookSizeBlocked(): String? = deepLook.blockedReason(_state.value.lookSize)

    fun cancelDeepLook() = _state.update { it.copy(confirmDeepLook = null) }

    /** Grok reads a sample of this tagline's posts (xAI's X search). Read only; the X API keys are not used. */
    fun confirmDeepLook() {
        val s = _state.value
        val tagline = s.confirmDeepLook ?: return
        val report = s.report ?: return
        _state.update { it.copy(confirmDeepLook = null, deepLooking = tagline, error = null, message = null) }
        viewModelScope.launch {
            try {
                // Earlier looks at this tagline go along, so Grok reads fresh posts and says what changed.
                val look = deepLook.look(tagline, report, previous = deepLookStore.history(tagline), size = s.lookSize)
                deepLookStore.addLook(look) // always kept, even if a new count arrived meanwhile
                if (_state.value.report?.fetchedAtMs == report.fetchedAtMs) {
                    _state.update { it.copy(deepLooking = null, deepLooks = it.deepLooks + (tagline to look)) }
                    refreshHistory()
                    publishTrend() // the same run again, now with this Deep look
                } else {
                    _state.update { it.copy(deepLooking = null) }
                    refreshHistory()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(deepLooking = null, error = "Deep look failed: ${e.message}") }
            }
        }
    }

    /**
     * Sends the current run with every AI result it has right now (none, a read-out, Deep looks on some taglines, or all).
     * Called after the count and again after each read-out or Deep look for the same count; the website updates that run.
     * A send still on its way is replaced by the newer, fuller one, and Retry always resends the fullest.
     */
    private fun publishTrend() {
        val s = _state.value
        val report = s.report ?: return
        try {
            publisher.publish(trendPayload(report, ZoneId.systemDefault(), s.analysis, s.deepLooks, s.regulars))
        } catch (e: PayloadException) {
            publisher.reject(MeterKind.TREND, e.message ?: "The website cannot take this result.")
        }
    }

    fun showInfo(text: String) = _state.update { it.copy(message = text, error = null) }

    fun showError(reason: String) = _state.update { it.copy(error = reason, message = null) }

    fun dismiss() = _state.update { it.copy(error = null, message = null) }
}
