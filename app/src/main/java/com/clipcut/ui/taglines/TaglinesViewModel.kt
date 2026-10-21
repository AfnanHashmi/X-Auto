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
import com.clipcut.domain.publish.SyncResult
import com.clipcut.domain.publish.WebsiteSync
import com.clipcut.domain.history.HistorySummary
import com.clipcut.domain.history.RunHistory
import com.clipcut.domain.taglines.Detected
import com.clipcut.domain.taglines.HOUR_MS
import com.clipcut.domain.taglines.ReadoutRecord
import com.clipcut.domain.taglines.TrendCheckException
import com.clipcut.domain.taglines.TrendCheckResult
import com.clipcut.domain.taglines.TrendCheckService
import com.clipcut.domain.taglines.TrendCheckStore
import com.clipcut.domain.taglines.TrendPlan
import com.clipcut.domain.taglines.TrendRange
import com.clipcut.domain.taglines.usdText
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
    /** The newest Trend check per tagline. Paid for, so kept on the phone and used by the read-out, share, website and ranking. */
    val checks: Map<String, TrendCheckResult> = emptyMap(),
    /** The Trend check panel, while it is open. */
    val trend: TrendUi? = null,
    /** Share as a long post (X Premium) with the full read-out and Trend checks; off keeps the post text to 280 characters. */
    val longPosts: Boolean = true,
    /** Which contestant each tagline counts for in the weekly ranking. */
    val contestants: Map<String, String> = emptyMap(),
    /** What the history holds from the last 7 days, for the "kept for the weekly ranking" line. */
    val collected: HistorySummary? = null,
) {
    val estimatedCostUsd: Double get() = estimatedCostUsd(taglines.size)
}

/** The Trend check panel for one tagline: the range found (editable), the exact numbers, and the sample once bought. */
data class TrendUi(
    val tagline: String,
    /** What is running right now, or null when idle. */
    val busy: String? = null,
    val range: TrendRange? = null,
    val check: TrendCheckResult? = null,
    val budgetText: String = "1.00",
    val error: String? = null,
    val notice: String? = null,
) {
    val budget: Double? get() = budgetText.toDoubleOrNull()
}

@HiltViewModel
class TaglinesViewModel @Inject constructor(
    private val service: TaglinesService,
    private val store: TaglineStore,
    private val publisher: WebsitePublisher,
    private val pool: XReadPool,
    private val analyst: TaglineAnalyst,
    private val trendService: TrendCheckService,
    private val trendStore: TrendCheckStore,
    private val history: RunHistory,
    private val contestants: ContestantStore,
    private val sync: WebsiteSync,
) : ViewModel() {
    /** Sends the latest run (with its AI read-out and Trend checks) to the website again. No X calls, no cost. */
    fun sendToWebsite() {
        when (val r = sync.sendTagline()) {
            SyncResult.Queued -> Unit // the banner shows Updating / Website updated / Not updated
            is SyncResult.Nothing -> _state.update { it.copy(error = r.reason, message = null) }
        }
    }

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
                checks = checksFor(r?.counts?.map { it.tagline }.orEmpty() + store.taglines()),
                analysis = r?.let { trendStore.readoutFor(it.fetchedAtMs) },
                longPosts = store.longPosts,
            )
        },
    )
    val state: StateFlow<TaglinesUiState> = _state.asStateFlow()

    private fun checksFor(taglines: List<String>): Map<String, TrendCheckResult> =
        taglines.distinct().mapNotNull { t -> trendStore.forTagline(t).firstOrNull()?.let { t to it } }.toMap()

    init {
        refreshChecks()
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

    private fun refreshChecks() = _state.update { st -> st.copy(checks = checksFor(st.taglines + st.report?.counts?.map { it.tagline }.orEmpty())) }

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
                    _state.update { it.copy(running = false, report = out.report, analysis = null, message = "Counted from X. About $${"%.3f".format(estimatedCostUsd(out.report.counts.size))} billed by X. Paid by ${paidByText(callsBefore, pool.calls.value)}.") }
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
                // Built on the newest Trend check of each tagline and the last read-out of an earlier count.
                val a = analyst.analyze(
                    report, ZoneId.systemDefault(), s.analysisHighQuality,
                    checks = s.checks,
                    previous = trendStore.previousReadout(report.fetchedAtMs),
                )
                trendStore.addReadout(ReadoutRecord(report.fetchedAtMs, report.counts.associate { it.tagline to it.all.total }, a))
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

    // ---- Trend check ------------------------------------------------------------------------------------------------

    fun trendSpentToday(): Double = trendService.spentToday()

    fun trendCostPerPost(): Double = trendService.costPerPost()

    fun trendCostMeasured(): Boolean = trendService.costPerPostIsMeasured()

    fun trendPayer(): String = trendService.payerText()

    /** What a Full analysis would buy at the typed budget; null when the budget is missing, out of range or too small. */
    fun trendPlan(): TrendPlan? {
        val t = _state.value.trend ?: return null
        val check = t.check ?: return null
        return t.budget?.let { b -> trendService.plan(check, b) }
    }

    /** Opens the panel for [tagline] and shows the last check of it, if there is one. Nothing is asked of X yet. */
    fun openTrend(tagline: String) {
        if (!trendService.isConfigured) {
            _state.update { it.copy(error = "Trend check needs the X app keys (RW, Main or DK) in local.properties.", message = null) }
            return
        }
        val saved = trendStore.forTagline(tagline).firstOrNull()
        _state.update {
            it.copy(trend = TrendUi(tagline, check = saved, range = saved?.let { c -> TrendRange(c.startMs, c.endMs, c.ended) }), error = null, message = null)
        }
    }

    fun closeTrend() = _state.update { if (it.trend?.busy != null) it else it.copy(trend = null) }

    private fun editTrend(f: (TrendUi) -> TrendUi) = _state.update { st -> st.trend?.let { st.copy(trend = f(it)) } ?: st }

    /** One counts call (about $0.005): finds when the trend started and ended in the last 7 days. */
    fun trendDetect() {
        val t = _state.value.trend ?: return
        if (t.busy != null) return
        editTrend { it.copy(busy = "Finding when the trend ran…", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val d: Detected = trendService.detect(t.tagline)
                editTrend {
                    if (d.range == null) {
                        it.copy(busy = null, error = "No clear trend for \"${t.tagline}\" in the last 7 days (it never reached 5 tweets an hour).")
                    } else {
                        it.copy(busy = null, range = d.range, check = it.check?.takeIf { c -> c.startMs == d.range.startMs }, notice = "Range found. Check it, then count the exact numbers.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrendCheckException) {
                editTrend { it.copy(busy = null, error = e.message) }
            }
        }
    }

    /** Moves the start or the end of the range by whole hours (the range is only ever whole hours). */
    fun trendShift(startHours: Int, endHours: Int) = editTrend { t ->
        val r = t.range ?: return@editTrend t
        val now = System.currentTimeMillis()
        val start = r.startMs + startHours * HOUR_MS
        val end = (r.endMs + endHours * HOUR_MS).coerceAtMost(now)
        if (end - start < HOUR_MS || now - start > 7 * 24 * HOUR_MS - 2 * HOUR_MS) t
        else t.copy(range = TrendRange(start, end, r.ended), check = t.check?.takeIf { it.startMs == start })
    }

    fun trendSetBudget(text: String) = editTrend { it.copy(budgetText = text.filter { c -> c.isDigit() || c == '.' }.take(5), error = null) }

    /** Four counts calls (about $0.02) for the chosen range: the exact retweet, reply and quote shares, launch and waves. */
    fun trendLive() {
        val t = _state.value.trend ?: return
        val range = t.range ?: return
        if (t.busy != null) return
        editTrend { it.copy(busy = "Counting the trend…", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val c = trendService.live(t.tagline, range)
                refreshChecks()
                editTrend { it.copy(busy = null, check = c, notice = "Exact numbers updated.") }
                publishTrend()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrendCheckException) {
                editTrend { it.copy(busy = null, error = e.message) }
            }
        }
    }

    /** Reads the planned posts inside the typed budget, then works out the sample figures and sentiment. */
    fun trendFull() {
        val t = _state.value.trend ?: return
        val check = t.check ?: return
        val budget = t.budget ?: return
        if (t.busy != null) return
        editTrend { it.copy(busy = "Reading posts and judging them… this can take a minute", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val c = trendService.full(check, budget)
                refreshChecks()
                editTrend { it.copy(busy = null, check = c, notice = "Done. X charged ${usdText(c.costUsd ?: 0.0)} in total for this trend.") }
                publishTrend()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrendCheckException) {
                // Whatever was bought is kept even when a step failed.
                val kept = trendStore.find(check.tagline, check.startMs)
                refreshChecks()
                editTrend { it.copy(busy = null, check = kept ?: it.check, error = e.message) }
            }
        }
    }

    /**
     * Sends the current run with every AI result it has right now (none, a read-out, Trend checks on some taglines, or all).
     * Called after the count and again after each read-out or Trend check for the same count; the website updates that run.
     * A send still on its way is replaced by the newer, fuller one, and Retry always resends the fullest.
     */
    private fun publishTrend() {
        val s = _state.value
        val report = s.report ?: return
        try {
            publisher.publish(trendPayload(report, ZoneId.systemDefault(), s.analysis, s.checks))
        } catch (e: PayloadException) {
            publisher.reject(MeterKind.TREND, e.message ?: "The website cannot take this result.")
        }
    }

    fun showInfo(text: String) = _state.update { it.copy(message = text, error = null) }

    fun showError(reason: String) = _state.update { it.copy(error = reason, message = null) }

    fun dismiss() = _state.update { it.copy(error = null, message = null) }
}
