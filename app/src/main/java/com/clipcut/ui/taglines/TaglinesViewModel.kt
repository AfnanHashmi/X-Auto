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
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.TREND_LOOKBACK_MS
import com.clipcut.domain.taglines.TREND_MAX_BUDGET_USD
import com.clipcut.domain.taglines.TREND_MIN_BUDGET_USD
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

/**
 * The Trend check panel for one tagline. The time range and the budget are the user's: nothing runs until both are set
 * and Run is tapped. "Suggest range" only fills the two fields (the user can still change them).
 */
data class TrendUi(
    val tagline: String,
    /** What is running right now, or null when idle. */
    val busy: String? = null,
    /** The range as the user set it (phone time, to the minute); null until picked. */
    val startMs: Long? = null,
    val endMs: Long? = null,
    /** Hourly counts from "Suggest range", so the plan can be shown before anything is bought. */
    val hours: List<HourBucket> = emptyList(),
    /** The saved check for exactly this range, if there is one. */
    val check: TrendCheckResult? = null,
    val budgetText: String = "",
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

    /** Why the range typed in the panel cannot be used, or null when it can. */
    fun trendRangeProblem(t: TrendUi, now: Long = System.currentTimeMillis()): String? {
        val start = t.startMs ?: return "Pick a start time."
        val end = t.endMs ?: return "Pick an end time."
        return when {
            end <= start -> "The end must be after the start."
            end - start < HOUR_MS -> "The range must be at least 1 hour."
            end > now + 60_000L -> "The end cannot be in the future."
            start < now - TREND_LOOKBACK_MS -> "X only keeps the last 7 days. Pick a later start."
            else -> null
        }
    }

    /** The user's range as the service takes it; null while it is incomplete or invalid. A range ending in the last 10 minutes counts as still running. */
    private fun rangeOf(t: TrendUi): TrendRange? {
        if (trendRangeProblem(t) != null) return null
        val now = System.currentTimeMillis()
        val end = minOf(t.endMs!!, now - 30_000L)
        return TrendRange(t.startMs!!, end, ended = now - end > 10 * 60_000L)
    }

    /** Why the typed budget cannot be used (range, today's limit), or null when it can. */
    fun trendBudgetProblem(t: TrendUi): String? {
        val b = t.budget ?: return "Type a budget."
        if (b < TREND_MIN_BUDGET_USD || b > TREND_MAX_BUDGET_USD) return "Budget must be ${usdText(TREND_MIN_BUDGET_USD)} to ${usdText(TREND_MAX_BUDGET_USD)}."
        return trendService.blockedReason(b)
    }

    /**
     * What the typed budget would buy, hour by hour. Needs hourly counts for the range: from a saved check of exactly this
     * range or from "Suggest range". Null otherwise (the screen then shows the simple estimate: budget ÷ price per post).
     */
    fun trendPlan(): TrendPlan? {
        val t = _state.value.trend ?: return null
        val range = rangeOf(t) ?: return null
        val budget = t.budget ?: return null
        val check = t.check
        val hourly = check?.hourly ?: t.hours.filter { it.startMs >= Math.floorDiv(range.startMs, HOUR_MS) * HOUR_MS && it.startMs < range.endMs }
        if (hourly.isEmpty()) return null
        return trendService.planHours(hourly, check?.readHours.orEmpty().toSet(), budget)
    }

    /** Opens the panel for [tagline]. Shows the last check of it with its range; otherwise the range and budget start empty. */
    fun openTrend(tagline: String) {
        if (!trendService.isConfigured) {
            _state.update { it.copy(error = "Trend check needs the X app keys (RW, Main or DK) in local.properties.", message = null) }
            return
        }
        val saved = trendStore.forTagline(tagline).firstOrNull()
        _state.update { it.copy(trend = TrendUi(tagline, startMs = saved?.startMs, endMs = saved?.endMs, check = saved), error = null, message = null) }
    }

    fun closeTrend() = _state.update { if (it.trend?.busy != null) it else it.copy(trend = null) }

    private fun editTrend(f: (TrendUi) -> TrendUi) = _state.update { st -> st.trend?.let { st.copy(trend = f(it)) } ?: st }

    /** The saved check for exactly this tagline and range, so its numbers show and a new run adds to its sample. */
    private fun savedFor(tagline: String, start: Long?, end: Long?): TrendCheckResult? =
        if (start == null || end == null) null else trendStore.find(tagline, start)?.takeIf { it.endMs == end }

    fun trendSetStart(ms: Long) = editTrend { it.copy(startMs = ms, check = savedFor(it.tagline, ms, it.endMs), error = null, notice = null) }

    fun trendSetEnd(ms: Long) = editTrend { it.copy(endMs = ms, check = savedFor(it.tagline, it.startMs, ms), error = null, notice = null) }

    fun trendSetBudget(text: String) = editTrend { it.copy(budgetText = text.filter { c -> c.isDigit() || c == '.' }.take(5), error = null) }

    /**
     * Optional help: one counts call (about $0.005) over the last 7 days, and the busy period found in it is written into the
     * start and end fields. It never runs by itself, and the user can change both fields afterwards.
     */
    fun trendSuggest() {
        val t = _state.value.trend ?: return
        if (t.busy != null) return
        editTrend { it.copy(busy = "Looking at the last 7 days of counts…", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val d: Detected = trendService.detect(t.tagline)
                val r = d.range
                editTrend {
                    if (r == null) {
                        it.copy(busy = null, hours = d.hourly, error = "No clear busy period for \"${t.tagline}\" in the last 7 days (it never reached 5 tweets an hour). Set the range yourself.")
                    } else {
                        it.copy(
                            busy = null, startMs = r.startMs, endMs = r.endMs, hours = d.hourly, check = savedFor(it.tagline, r.startMs, r.endMs),
                            notice = "Suggested from the counts" + (if (r.ended) "" else " (still running)") + ". Change the start or end if you like.",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrendCheckException) {
                editTrend { it.copy(busy = null, error = e.message) }
            }
        }
    }

    /** Refresh only the exact numbers for the chosen range: four counts calls (about $0.02). Reads no posts. */
    fun trendLive() {
        val t = _state.value.trend ?: return
        if (t.busy != null) return
        val range = rangeOf(t) ?: run { editTrend { it.copy(error = trendRangeProblem(t)) }; return }
        editTrend { it.copy(busy = "Counting the range…", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val c = trendService.live(t.tagline, range)
                refreshChecks()
                editTrend { it.copy(busy = null, startMs = c.startMs, endMs = c.endMs, check = c, notice = "Exact numbers updated.") }
                publishTrend()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrendCheckException) {
                editTrend { it.copy(busy = null, error = e.message) }
            }
        }
    }

    /**
     * Run: counts the chosen range (about $0.02), then reads posts inside the typed budget and works out the sample figures
     * and sentiment. Nothing is spent when the range or the budget is not valid.
     */
    fun trendRun() {
        val t = _state.value.trend ?: return
        if (t.busy != null) return
        val range = rangeOf(t) ?: run { editTrend { it.copy(error = trendRangeProblem(t)) }; return }
        trendBudgetProblem(t)?.let { p -> editTrend { it.copy(error = p) }; return }
        val budget = t.budget ?: return
        editTrend { it.copy(busy = "Counting the range…", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val counted = trendService.live(t.tagline, range)
                editTrend { it.copy(startMs = counted.startMs, endMs = counted.endMs, check = counted) }
                if (counted.shares.all == 0L) {
                    refreshChecks()
                    editTrend { it.copy(busy = null, error = "No tweets with \"${t.tagline}\" in this range, so no posts were bought.") }
                    return@launch
                }
                editTrend { it.copy(busy = "Reading posts and judging them… this can take a minute") }
                val c = trendService.full(counted, budget)
                refreshChecks()
                editTrend { it.copy(busy = null, check = c, notice = "Done. X charged ${usdText(c.costUsd ?: 0.0)} in all for posts in this range.") }
                publishTrend()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrendCheckException) {
                // Whatever was bought is kept even when a step failed.
                refreshChecks()
                editTrend { it.copy(busy = null, check = trendStore.find(t.tagline, range.startMs) ?: it.check, error = e.message) }
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
