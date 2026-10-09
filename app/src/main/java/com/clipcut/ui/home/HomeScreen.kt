package com.clipcut.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import com.clipcut.data.db.UnfollowDao
import androidx.lifecycle.viewModelScope
import com.clipcut.data.radar.RadarCache
import com.clipcut.domain.radar.Outcome
import com.clipcut.domain.radar.RadarService
import com.clipcut.domain.radar.RadarSummary
import com.clipcut.domain.unfollows.UnfollowSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.clipcut.data.x.BbLogsCounter
import com.clipcut.data.x.MediaAccountSelector
import com.clipcut.domain.api.MediaAccount
import com.clipcut.lan.LanBufferActivity
import com.clipcut.ui.caption.CaptionGeneratorActivity
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.deletion.DeletionActivity
import com.clipcut.ui.trends.TrendRadarActivity
import com.clipcut.ui.unfollows.UnfollowsActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val counter: BbLogsCounter,
    private val selector: MediaAccountSelector,
    unfollowDao: UnfollowDao,
    private val radar: RadarService,
    private val radarCache: RadarCache,
) : ViewModel() {
    private val _radar = MutableStateFlow(radarCache.load())

    /** The Trend Radar card: shown from the last known state at once, then refreshed (read only) when the screen starts. */
    val radarSummary: StateFlow<RadarSummary> = _radar.asStateFlow()

    fun refreshRadar() {
        if (!radar.isConfigured) return
        viewModelScope.launch {
            (radar.report() as? Outcome.Ok)?.let {
                val s = RadarSummary.of(it.value)
                radarCache.save(s)
                _radar.value = s
            }
        }
    }

    /** Today's unfollows and group kicks, live from the local log, for the Unfollowers card. */
    val unfollowSummary: Flow<UnfollowSummary> = unfollowDao.observeAll().map { UnfollowSummary.of(it) }

    /** The media account that clips are posted to and that Trigger Deletion cleans up. */
    val active: MediaAccount get() = selector.active

    fun select(account: MediaAccount) {
        if (account.isConfigured) selector.active = account
    }

    /** What the next post on the selected account will be captioned. */
    fun nextCaption(): String = counter.nextCaption(selector.active)
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier, viewModel: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    // Re-read on every return to this screen: posting a clip or changing the schedule happens elsewhere.
    var caption by remember { mutableStateOf(viewModel.nextCaption()) }
    var active by remember { mutableStateOf(viewModel.active) }
    val summary by viewModel.unfollowSummary.collectAsState(initial = UnfollowSummary.EMPTY)
    val radar by viewModel.radarSummary.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                caption = viewModel.nextCaption()
                active = viewModel.active
                viewModel.refreshRadar()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Screen(title = "X Auto", subtitle = "Bigg Boss clipping and posting", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountSwitcher(
                active = active,
                onSelect = { account ->
                    viewModel.select(account)
                    active = viewModel.active
                    caption = viewModel.nextCaption()
                },
            )
            FeatureCard(
                title = "Clip Cut",
                body = "Watch the live feed, mark in and out, export and post.",
                status = "${active.label}: next post $caption",
                onClick = { context.startActivity(Intent(context, LanBufferActivity::class.java)) },
            )
            FeatureCard(
                title = "Caption Generator",
                body = "Write and enhance a caption with moods and hashtags, then copy it.",
                status = "No clip needed",
                onClick = { CaptionGeneratorActivity.start(context) },
            )
            TrendRadarCard(summary = radar, onClick = { TrendRadarActivity.start(context) })
            UnfollowersCard(summary = summary, onClick = { UnfollowsActivity.start(context) })
            FeatureCard(
                title = "Trigger Deletion",
                body = "Review and delete the media account's posts from a chosen time period.",
                status = "${active.label} account only",
                onClick = { DeletionActivity.start(context) },
            )
        }
        Text(
            "Personal tool. Nothing is posted or deleted without you asking.",
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Chooses which media account clips are posted to and which one Trigger Deletion works on. An account
 * whose keys are not in local.properties is shown but cannot be selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSwitcher(active: MediaAccount, onSelect: (MediaAccount) -> Unit) {
    SectionCard("Media account") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            MediaAccount.entries.forEachIndexed { index, account ->
                SegmentedButton(
                    selected = account == active,
                    onClick = { onSelect(account) },
                    enabled = account.isConfigured,
                    shape = SegmentedButtonDefaults.itemShape(index, MediaAccount.entries.size),
                ) { Text(account.label) }
            }
        }
        Text(
            "Posts and deletions use @${active.handle}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MediaAccount.entries.filter { !it.isConfigured }.forEach { account ->
            Text(
                "${account.label} is not set up: its keys are missing from local.properties.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The Trend Radar card: whether today's session is on, how many topics are rising, and when it last looked.
 * It turns coloured while something is rising.
 */
@Composable
private fun TrendRadarCard(summary: RadarSummary, onClick: () -> Unit) {
    val rising = summary.rising > 0
    val container = if (rising) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface
    val phase = when (summary.phase) {
        "running" -> "Session on" + (summary.budgetUsd?.let { " · \$%.2f of \$%.2f".format(summary.spentUsd, it) } ?: "")
        "armed" -> "Session armed, waiting for the window"
        "ended" -> "Session ended"
        else -> "Session off"
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Trend Radar", style = MaterialTheme.typography.titleLarge)
            Text(
                "What X is saying about Bigg Boss 20: rising topics, questions and ideas for your posts. Reads only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                CountPill(phase, if (summary.phase == "running") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                CountPill("${summary.rising} rising", if (rising) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            summary.lastRunAtMs?.let {
                val t = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
                Text((if (summary.sample) "Showing a sample report from " else "Last scan ") + t, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The Unfollowers card. It shows today's two numbers at a glance and tints itself when someone was kicked out of the
 * group chat today (live) or would have been (dry run).
 */
@Composable
private fun UnfollowersCard(summary: UnfollowSummary, onClick: () -> Unit) {
    val kicked = summary.kickedToday > 0
    val container = if (kicked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Unfollowers", style = MaterialTheme.typography.titleLarge)
            Text(
                "See who unfollowed your main account, and who was kicked out of the group chat because of it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                CountPill("${summary.unfollowedToday} unfollowed today", MaterialTheme.colorScheme.secondary)
                CountPill(
                    "${summary.kickedToday} kicked from group",
                    if (kicked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary.wouldKickToday > 0) {
                Text(
                    "${summary.wouldKickToday} would be kicked (dry run)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (summary.waiting > 0) {
                Text(
                    "${summary.waiting} waiting for their re-check",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CountPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = CircleShape) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun FeatureCard(title: String, body: String, status: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(6.dp),
                    content = {},
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
