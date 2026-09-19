package com.clipcut.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clipcut.data.x.BbLogsCounter
import com.clipcut.lan.LanBufferActivity
import com.clipcut.ui.deletion.DeletionActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(private val counter: BbLogsCounter) : ViewModel() {
    /** What the next media-account post will be captioned. */
    fun nextCaption(): String = counter.nextCaption()
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier, viewModel: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    // Re-read on every return to this screen: posting a clip advances the counter behind our back.
    var nextCaption by remember { mutableStateOf(viewModel.nextCaption()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) nextCaption = viewModel.nextCaption()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("X Auto", style = MaterialTheme.typography.headlineMedium)
        FeatureCard(
            title = "Clip Cut",
            body = "Cut a clip from the live feed, post it to the media account, and copy the caption for the main account.",
            onClick = { context.startActivity(Intent(context, LanBufferActivity::class.java)) },
        )
        FeatureCard(
            title = "Trigger Deletion",
            body = "Review and delete the media account's BB LOGS posts from a chosen time period.",
            onClick = { DeletionActivity.start(context) },
        )
        Text("Next media post: $nextCaption", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FeatureCard(title: String, body: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
