package com.clipcut.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.clipcut.domain.unfollows.UnfollowSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.clipcut.data.x.BbLogsCounter
import com.clipcut.data.x.MediaAccountSelector
import com.clipcut.domain.api.MediaAccount
import com.clipcut.lan.LanBufferActivity
import com.clipcut.ui.caption.CaptionGeneratorActivity
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.NavTile
import com.clipcut.ui.components.Panel
import com.clipcut.ui.components.Pill
import com.clipcut.ui.deletion.DeletionActivity
import com.clipcut.ui.bossmeter.BossMeterActivity
import com.clipcut.ui.taglines.TaglinesActivity
import com.clipcut.ui.top5.Top5Activity
import com.clipcut.ui.unfollows.UnfollowsActivity
import com.clipcut.domain.api.Balance
import com.clipcut.domain.api.Balances
import com.clipcut.domain.api.XApp
import com.clipcut.domain.api.XReadPool
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val counter: BbLogsCounter,
    private val selector: MediaAccountSelector,
    unfollowDao: UnfollowDao,
    private val pool: XReadPool,
) : ViewModel() {
    /** Each X app's prepaid balance, from X's own usage endpoint (free to read). */
    val balances: StateFlow<Balances> = pool.balances

    /** Re-reads the balances; without [force] at most every two minutes. */
    fun refreshBalances(force: Boolean = false) {
        viewModelScope.launch { pool.refreshBalances(force) }
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
    val balances by viewModel.balances.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.refreshBalances()
                caption = viewModel.nextCaption()
                active = viewModel.active
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Screen(title = "X Auto", subtitle = "Bigg Boss clipping and posting", modifier = modifier) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CreditsStrip(balances, onRefresh = { viewModel.refreshBalances(force = true) })
            AccountSwitcher(
                active = active,
                onSelect = { account ->
                    viewModel.select(account)
                    active = viewModel.active
                    caption = viewModel.nextCaption()
                },
            )
            ClipCutHero(account = active.label, caption = caption, onClick = { context.startActivity(Intent(context, LanBufferActivity::class.java)) })
            TileRow(
                { NavTile("Captions", "Write and polish a caption", onClick = { CaptionGeneratorActivity.start(context) }, modifier = it) },
                {
                    val kicked = summary.kickedToday > 0
                    NavTile(
                        "Unfollowers",
                        "${summary.unfollowedToday} today" + if (kicked) " · ${summary.kickedToday} kicked" else "",
                        onClick = { UnfollowsActivity.start(context) },
                        modifier = it,
                        accent = if (kicked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                        tint = if (kicked) MaterialTheme.colorScheme.primaryContainer else null,
                    )
                },
            )
            TileRow(
                { NavTile("Boss Meter", "Hashtag votes in a thread", onClick = { BossMeterActivity.start(context) }, modifier = it) },
                { NavTile("Taglines", "Tweets per tagline, 24 h", onClick = { TaglinesActivity.start(context) }, modifier = it) },
            )
            NavTile("Top 5 of the week", "Weekly ranking for the website", onClick = { Top5Activity.start(context) }, modifier = Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.tertiary)
            NavTile(
                "Trigger Deletion",
                "Review and delete posts from @${active.handle}",
                onClick = { DeletionActivity.start(context) },
                modifier = Modifier.fillMaxWidth(),
                accent = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "Nothing is posted or deleted without you asking.",
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Two tiles side by side, equal width and height. */
@Composable
private fun TileRow(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        left(Modifier.weight(1f).fillMaxHeight())
        right(Modifier.weight(1f).fillMaxHeight())
    }
}

/** The main job, given the most room: where the next clip goes and what it will be called. */
@Composable
private fun ClipCutHero(account: String, caption: String, onClick: () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, onClick = onClick) {
        Row(modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Clip Cut", style = MaterialTheme.typography.headlineSmall)
                Text("Live feed · mark in and out · export and post", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Pill("Next on $account: $caption", MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The X credit left on each account's app, in one slim strip. Under $1 the amount turns red. Tap to re-check (free). */
@Composable
private fun CreditsStrip(b: Balances, onRefresh: () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth(), onClick = onRefresh) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            XApp.entries.forEachIndexed { i, app ->
                if (i > 0) Box(Modifier.width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
                val low = b.isLow(app)
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(app.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when (val bal = b.byApp[app]) {
                            is Balance.Known -> "$" + "%.2f".format(bal.usd)
                            is Balance.Unavailable -> "?"
                            Balance.Unchecked -> "…"
                            Balance.NotSetUp, null -> "–"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Text(
                if (b.refreshing) "…" else "↻",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

/**
 * Chooses which media account clips are posted to and which one Trigger Deletion works on. An account
 * whose keys are not in local.properties is shown but cannot be selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSwitcher(active: MediaAccount, onSelect: (MediaAccount) -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("POSTING AS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("@${active.handle}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.width(150.dp)) {
                    MediaAccount.entries.forEachIndexed { index, account ->
                        SegmentedButton(
                            selected = account == active,
                            onClick = { onSelect(account) },
                            enabled = account.isConfigured,
                            shape = SegmentedButtonDefaults.itemShape(index, MediaAccount.entries.size),
                            icon = {},
                        ) { Text(account.label) }
                    }
                }
            }
            MediaAccount.entries.filter { !it.isConfigured }.forEach { account ->
                Text(
                    "${account.label} is not set up: its keys are missing from local.properties.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
