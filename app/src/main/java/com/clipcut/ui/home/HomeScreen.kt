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
import com.clipcut.domain.broadcast.BroadcastScheduler
import com.clipcut.domain.broadcast.BroadcastSettings
import com.clipcut.lan.LanBufferActivity
import com.clipcut.ui.broadcast.BroadcastActivity
import com.clipcut.ui.components.Screen
import com.clipcut.ui.deletion.DeletionActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val counter: BbLogsCounter,
    private val broadcast: BroadcastSettings,
    private val scheduler: BroadcastScheduler,
) : ViewModel() {
    fun nextCaption(): String = counter.nextCaption()

    fun broadcastStatus(): String = if (broadcast.enabled) {
        "Next send " + NEXT.format(scheduler.nextRunAt())
    } else {
        "Off"
    }

    private companion object {
        val NEXT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
    }
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier, viewModel: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    // Re-read on every return to this screen: posting a clip or changing the schedule happens elsewhere.
    var caption by remember { mutableStateOf(viewModel.nextCaption()) }
    var broadcast by remember { mutableStateOf(viewModel.broadcastStatus()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                caption = viewModel.nextCaption()
                broadcast = viewModel.broadcastStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Screen(title = "X Auto", subtitle = "Bigg Boss clipping and posting", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard(
                title = "Clip Cut",
                body = "Watch the live feed, mark in and out, export and post.",
                status = "Next post $caption",
                onClick = { context.startActivity(Intent(context, LanBufferActivity::class.java)) },
            )
            FeatureCard(
                title = "Daily Link",
                body = "Send today's viewing link to your X and Telegram groups every morning.",
                status = broadcast,
                onClick = { BroadcastActivity.start(context) },
            )
            FeatureCard(
                title = "Trigger Deletion",
                body = "Review and delete the media account's posts from a chosen time period.",
                status = "Media account only",
                onClick = { DeletionActivity.start(context) },
            )
        }
        Text(
            "Personal tool. Nothing posts without you asking, except the daily link when it is switched on.",
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
