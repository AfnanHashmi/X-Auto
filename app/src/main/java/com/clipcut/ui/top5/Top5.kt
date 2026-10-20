package com.clipcut.ui.top5

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.domain.bossmeter.BossMeterStore
import com.clipcut.domain.bossmeter.IgBossApi
import com.clipcut.domain.bossmeter.IgException
import com.clipcut.domain.bossmeter.shortcodeFrom
import com.clipcut.domain.history.ContestantStore
import com.clipcut.domain.history.mentionKeys
import kotlinx.coroutines.launch
import com.clipcut.domain.history.RELEVANCE_WEIGHTS
import com.clipcut.domain.history.Relevance
import com.clipcut.domain.history.RunHistory
import com.clipcut.domain.history.contestantKey
import com.clipcut.domain.history.relevance
import com.clipcut.domain.history.weekStart
import com.clipcut.domain.history.weekStats
import com.clipcut.domain.publish.MeterKind
import com.clipcut.domain.publish.PayloadException
import com.clipcut.domain.publish.PublishStatus
import com.clipcut.domain.publish.Top5Row
import com.clipcut.domain.publish.WebsitePublisher
import com.clipcut.domain.publish.top5Payload
import com.clipcut.domain.taglines.dateTimeText
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone
import com.clipcut.ui.publish.PublishBanner
import com.clipcut.ui.theme.ClipCutTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** The week's label, from its first day: "Week of 16 Oct". */
fun weekLabel(weekFromMs: Long, zone: ZoneId): String =
    "Week of " + Instant.ofEpochMilli(weekFromMs).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))

/** The list as the data gives it: this week's five highest relevance scores, nobody added by hand and nothing typed. */
data class Top5UiState(
    val scores: List<Relevance> = emptyList(),
    val weekFromMs: Long = 0L,
    val week: String = "",
    val error: String? = null,
    /** Instagram comments mentioning each contestant this week (display name to count), once the server has answered. */
    val igMentions: Map<String, Long>? = null,
    val igNote: String? = null,
) {
    val top: List<Relevance> get() = scores.filter { it.score > 0 }.take(5)
}

@HiltViewModel
class Top5ViewModel @Inject constructor(
    private val publisher: WebsitePublisher,
    private val history: RunHistory,
    private val contestants: ContestantStore,
    private val instagram: IgBossApi,
    private val bossStore: BossMeterStore,
) : ViewModel() {
    private val _state = MutableStateFlow(Top5UiState())
    val state: StateFlow<Top5UiState> = _state.asStateFlow()

    val publishStatus: StateFlow<PublishStatus> = publisher.status.map { it.getValue(MeterKind.TOP5) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PublishStatus.Idle)

    init {
        refresh()
    }

    /** Recomputes this week's relevance from everything collected since Friday 6 PM, Instagram mentions included. No X calls. */
    fun refresh() {
        recompute()
        viewModelScope.launch { if (loadInstagram()) recompute() }
    }

    private fun recompute() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val from = weekStart(now, zone)
        val links = contestants.snapshot()
        val scores = relevance(weekStats(history, { text -> links[contestantKey(text)] ?: contestants.resolve(text) }, from, now, _state.value.igMentions))
        _state.update { it.copy(scores = scores, weekFromMs = from, week = weekLabel(from, zone), error = null) }
    }

    /**
     * Asks the Instagram server how many comments (all of them, replies too) mention each contestant since the week began:
     * votes, wrong hashtags, @handles and plain names. True when the numbers arrived. Not set up: the score is X only.
     */
    private suspend fun loadInstagram(): Boolean {
        val code = shortcodeFrom(bossStore.igPost)
        if (!instagram.isConfigured || code == null) {
            _state.update { it.copy(igMentions = null, igNote = "Instagram is not included (set up the Instagram post on the Boss Meter screen).") }
            return false
        }
        val now = System.currentTimeMillis()
        val from = weekStart(now, ZoneId.systemDefault())
        return try {
            val m = instagram.mentions(code, mentionKeys(), from / 1000, now / 1000)
            _state.update { it.copy(igMentions = m, igNote = "Instagram included: ${"%,d".format(m.values.sum())} mentions counted since the week began.") }
            true
        } catch (e: IgException) {
            _state.update { it.copy(igMentions = null, igNote = "Instagram is NOT included: ${e.message}") }
            false
        }
    }

    /** Publishes exactly the list on screen, with each score and the facts behind it. Nothing is sent if the data gives no list. */
    fun publish() {
        viewModelScope.launch {
            recompute()
            val ok = loadInstagram()
            // With Instagram set up but unreachable the list would rest on part of the data, so nothing is sent.
            if (!ok && instagram.isConfigured && shortcodeFrom(bossStore.igPost) != null) {
                _state.update { it.copy(error = "Not sent: the Instagram numbers could not be read, so the list would be incomplete. Check that the PC is on and Tailscale is connected, then try again.") }
                return@launch
            }
            recompute()
            val s = _state.value
            val rows = s.top.map { r -> Top5Row(r.week.name, number = r.score.toString(), facts = "Relevance ${r.score}/100: ${r.factsLine}") }
            try {
                publisher.publish(top5Payload(s.week, rows, System.currentTimeMillis()))
            } catch (e: PayloadException) {
                _state.update { it.copy(error = if (rows.isEmpty()) "No data this week yet: run Tagline Counter or Boss Meter, and link taglines and hashtags to contestants." else e.message) }
            }
        }
    }

    fun retry() = publisher.retry(MeterKind.TOP5)
}

@Composable
fun Top5Screen(onExit: () -> Unit, viewModel: Top5ViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val publish by viewModel.publishStatus.collectAsState()
    val zone = ZoneId.systemDefault()
    Screen(title = "Top 5 of the week", subtitle = "Built only from this week's data. Nothing here is typed in.") {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(state.week.ifEmpty { "This week" }) {
                Text(
                    "From everything collected since ${dateTimeText(state.weekFromMs, zone)}: tagline tweets, original tweets, tagline wins, " +
                        "peak hour, Boss Meter vote share and wins, trending positions, and Instagram comments that mention them (votes, " +
                        "other hashtags, @handles and names in text). Each fact is compared with the week's best. " +
                        "The week resets every Friday 6 PM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Weights: " + RELEVANCE_WEIGHTS.entries.joinToString(" · ") { (k, v) -> "$k ${(v * 100).toInt()}%" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.top.isEmpty()) {
                Text(
                    "No data this week yet. Run Tagline Counter or Boss Meter; each tagline and hashtag needs a contestant label.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.top.forEachIndexed { i, r ->
                SectionCard("#${i + 1}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.week.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${r.score}/100", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(r.factsLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.igNote?.let { StatusRow(it, if (it.contains("NOT")) Tone.Bad else Tone.Neutral) }
            state.error?.let { StatusRow(it, Tone.Bad) }
            PublishBanner(publish, onRetry = viewModel::retry)
            OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) { Text("↻ Recalculate from this week's data") }
            Button(
                onClick = viewModel::publish,
                enabled = publish != PublishStatus.Sending && state.top.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Publish to website") }
        }
        TextButton(onClick = onExit) { Text("Back") }
    }
}

@AndroidEntryPoint
class Top5Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipCutTheme {
                Surface(modifier = Modifier.fillMaxSize()) { Top5Screen(onExit = { finish() }) }
            }
        }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, Top5Activity::class.java))
    }
}
