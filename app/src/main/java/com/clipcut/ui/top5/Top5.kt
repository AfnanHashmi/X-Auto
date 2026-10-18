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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.clipcut.domain.history.ContestantStore
import com.clipcut.domain.history.RELEVANCE_WEIGHTS
import com.clipcut.domain.history.Relevance
import com.clipcut.domain.history.RunHistory
import com.clipcut.domain.history.relevance
import com.clipcut.domain.history.weekStart
import com.clipcut.domain.history.weekStats
import com.clipcut.domain.publish.MAX_LABEL
import com.clipcut.domain.publish.MAX_TOP5_NOTE
import com.clipcut.domain.publish.MAX_WEEK_LABEL
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import javax.inject.Inject

/** The last Top 5 typed in, kept so it can be edited and published again. */
class Top5Store @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("top5", Context.MODE_PRIVATE)

    var week: String
        get() = prefs.getString("week", "").orEmpty()
        set(v) = prefs.edit().putString("week", v).apply()

    var attachScores: Boolean
        get() = prefs.getBoolean("attach_scores", true)
        set(v) = prefs.edit().putBoolean("attach_scores", v).apply()

    var rows: List<Top5Row>
        get() = prefs.getString("rows", null)?.let { raw ->
            runCatching { JSONArray(raw).let { a -> List(a.length()) { a.getJSONObject(it).let { o -> Top5Row(o.optString("name"), note = o.optString("note")) } } } }.getOrNull()
        }.orEmpty().let { it + List((5 - it.size).coerceAtLeast(0)) { Top5Row("") } }.take(5)
        set(v) = prefs.edit().putString("rows", JSONArray(v.map { JSONObject().put("name", it.name).put("note", it.note) }).toString()).apply()
}

data class Top5UiState(
    val week: String = "",
    val rows: List<Top5Row> = List(5) { Top5Row("") },
    val error: String? = null,
    /** This week's relevance per contestant (lowercase name), highest first. */
    val scores: List<Relevance> = emptyList(),
    val weekFromMs: Long = 0L,
    val attachScores: Boolean = true,
) {
    fun scoreFor(name: String): Relevance? = scores.firstOrNull { it.week.name.equals(name.trim(), ignoreCase = true) }
}

@HiltViewModel
class Top5ViewModel @Inject constructor(
    private val store: Top5Store,
    private val publisher: WebsitePublisher,
    private val history: RunHistory,
    private val contestants: ContestantStore,
) : ViewModel() {
    private val _state = MutableStateFlow(Top5UiState(store.week, store.rows.map { it.copy(name = fullName(it.name)) }, attachScores = store.attachScores))
    val state: StateFlow<Top5UiState> = _state.asStateFlow()

    val publishStatus: StateFlow<PublishStatus> = publisher.status.map { it.getValue(MeterKind.TOP5) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PublishStatus.Idle)

    init {
        refreshScores()
    }

    /** Recomputes this week's relevance from everything collected since Friday 6 PM. Free: no calls. */
    fun refreshScores() {
        val now = System.currentTimeMillis()
        val from = weekStart(now, ZoneId.systemDefault())
        val links = contestants.snapshot()
        val scores = relevance(weekStats(history, { text -> links[com.clipcut.domain.history.contestantKey(text)] ?: contestants.resolve(text) }, from, now))
        _state.update { it.copy(scores = scores, weekFromMs = from) }
    }

    fun setWeek(v: String) {
        val w = v.take(MAX_WEEK_LABEL)
        store.week = w
        _state.update { it.copy(week = w, error = null) }
    }

    fun setRow(i: Int, row: Top5Row) {
        val rows = _state.value.rows.toMutableList().also { it[i] = row }
        store.rows = rows
        _state.update { it.copy(rows = rows, error = null) }
    }

    /** A name the roster knows becomes its full name ("Qazi" → "Qazi Touqeer"); anything else is left as typed. */
    private fun fullName(name: String): String = name.trim().takeIf { it.isNotEmpty() }?.let { n -> contestants.resolve(n) ?: n } ?: name

    fun setAttachScores(on: Boolean) {
        store.attachScores = on
        _state.update { it.copy(attachScores = on) }
    }

    /** Puts this week's five highest scores in the rows (notes typed for the same names are kept). */
    fun fillFromData() {
        refreshScores()
        val s = _state.value
        val notes = s.rows.associate { it.name.trim().lowercase() to it.note }
        val rows = s.scores.take(5).map { Top5Row(it.week.name, note = notes[it.week.name.lowercase()].orEmpty()) }
        val filled = rows + List(5 - rows.size) { Top5Row("") }
        store.rows = filled
        _state.update { it.copy(rows = filled, error = if (rows.isEmpty()) "No data this week yet: run Tagline Counter or Boss Meter and link taglines to contestants." else null) }
    }

    /** Checks the list against the website's rules first; nothing is sent if it would be refused. */
    fun publish() {
        refreshScores()
        val s = _state.value
        val rows = s.rows.map { r0 ->
            val r = r0.copy(name = fullName(r0.name))
            val sc = if (s.attachScores && r.name.isNotBlank()) s.scoreFor(r.name) else null
            r.copy(number = sc?.score?.toString().orEmpty(), facts = sc?.let { "Relevance ${it.score}/100: ${it.factsLine}" }.orEmpty())
        }
        try {
            publisher.publish(top5Payload(s.week, rows, System.currentTimeMillis()))
            store.rows = rows.map { it.copy(number = "", facts = "") }
            _state.update { it.copy(rows = store.rows) } // the screen shows the full names that were sent
        } catch (e: PayloadException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun retry() = publisher.retry(MeterKind.TOP5)
}

@Composable
fun Top5Screen(onExit: () -> Unit, viewModel: Top5ViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val publish by viewModel.publishStatus.collectAsState()
    val zone = ZoneId.systemDefault()
    Screen(title = "Top 5 of the week", subtitle = "Published to the website's Updates. The newest list replaces the old one.") {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                state.week, viewModel::setWeek, label = { Text("Week (optional)") }, placeholder = { Text("e.g. Week 3") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            SectionCard("Relevance score") {
                Text(
                    "From everything collected since ${dateTimeText(state.weekFromMs, zone)}: tagline tweets, original tweets, tagline wins, " +
                        "peak hour, Boss Meter vote share and wins, trending positions. Each fact is compared with the week's best; " +
                        "it resets every Friday 6 PM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Weights: " + RELEVANCE_WEIGHTS.entries.joinToString(" · ") { (k, v) -> "$k ${(v * 100).toInt()}%" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Attach score and facts to each contestant", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(checked = state.attachScores, onCheckedChange = viewModel::setAttachScores)
                }
                OutlinedButton(onClick = viewModel::fillFromData, modifier = Modifier.fillMaxWidth()) { Text("Fill from this week's data") }
            }
            state.rows.forEachIndexed { i, row ->
                SectionCard("#${i + 1}") {
                    OutlinedTextField(
                        row.name, { viewModel.setRow(i, row.copy(name = it.take(MAX_LABEL))) },
                        label = { Text(if (i == 0) "Contestant (required)" else "Contestant") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        row.note, { viewModel.setRow(i, row.copy(note = it.take(MAX_TOP5_NOTE))) },
                        label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    if (row.name.isNotBlank()) {
                        val sc = state.scoreFor(row.name)
                        Text(
                            sc?.let { "Relevance ${it.score}/100 · ${it.factsLine}" } ?: "No data for this name this week (no score sent).",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sc != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "Empty rows are not sent. Order is the rank.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let { StatusRow(it, Tone.Bad) }
            PublishBanner(publish, onRetry = viewModel::retry)
            Button(onClick = viewModel::publish, enabled = publish != PublishStatus.Sending, modifier = Modifier.fillMaxWidth()) { Text("Publish to website") }
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
