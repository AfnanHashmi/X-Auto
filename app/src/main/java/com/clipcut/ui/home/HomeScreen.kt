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
import com.clipcut.data.x.BbLogsCounter
import com.clipcut.data.x.MediaAccountSelector
import com.clipcut.domain.api.MediaAccount
import com.clipcut.lan.LanBufferActivity
import com.clipcut.ui.caption.CaptionGeneratorActivity
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.deletion.DeletionActivity
import com.clipcut.ui.gcprune.GcPruneActivity
import com.clipcut.ui.unfollows.UnfollowsActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val counter: BbLogsCounter,
    private val selector: MediaAccountSelector,
) : ViewModel() {
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                caption = viewModel.nextCaption()
                active = viewModel.active
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
            FeatureCard(
                title = "GC Prune",
                body = "Paste usernames, check who they are, and remove them from a group chat safely.",
                status = "Lookup removes nobody",
                onClick = { GcPruneActivity.start(context) },
            )
            FeatureCard(
                title = "Unfollowers",
                body = "See who unfollowed your main account, with the time, as X reports it.",
                status = "Main account only",
                onClick = { UnfollowsActivity.start(context) },
            )
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
